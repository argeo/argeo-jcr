package org.argeo.maintenance;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.argeo.api.acr.ldap.Distinguished;
import org.argeo.api.acr.spi.ProvidedRepository;
import org.argeo.api.cms.CmsLog;
import org.argeo.api.cms.transaction.WorkTransaction;
import org.argeo.cms.jcr.CmsJcrUtils;
import org.argeo.jcr.Jcr;
import org.argeo.jcr.JcrUtils;
import org.osgi.service.useradmin.Group;
import org.osgi.service.useradmin.Role;
import org.osgi.service.useradmin.UserAdmin;

/** Make sure roles and access rights are properly configured. */
public abstract class AbstractMaintenanceService {
	private final static CmsLog log = CmsLog.getLog(AbstractMaintenanceService.class);

	private Repository repository;
//	private UserAdminService userAdminService;
	private UserAdmin userAdmin;
	private WorkTransaction userTransaction;

	private ProvidedRepository contentRepository;

	public void init() {
		makeSureRolesExists(getRequiredRoles());
		configureStandardRoles();

		Set<String> workspaceNames = getWorkspaceNames();
		if (workspaceNames == null || workspaceNames.isEmpty()) {
			configureJcr(repository, null);
		} else {
			for (String workspaceName : workspaceNames)
				configureJcr(repository, workspaceName);
		}
	}

	/** Configures a workspace. */
	protected void configureJcr(Repository repository, String workspaceName) {
		Session adminSession;
		try {
			adminSession = CmsJcrUtils.openDataAdminSession(repository, workspaceName);
		} catch (RuntimeException e1) {
			if (e1.getCause() != null && e1.getCause() instanceof NoSuchWorkspaceException) {
				Session defaultAdminSession = CmsJcrUtils.openDataAdminSession(repository, null);
				try {
					defaultAdminSession.getWorkspace().createWorkspace(workspaceName);
					log.info("Created JCR workspace " + workspaceName);
				} catch (RepositoryException e) {
					throw new IllegalStateException("Cannot create workspace " + workspaceName, e);
				} finally {
					Jcr.logout(defaultAdminSession);
				}
				adminSession = CmsJcrUtils.openDataAdminSession(repository, workspaceName);
			} else
				throw e1;
		}
		try {
			if (prepareJcrTree(adminSession)) {
				configurePrivileges(adminSession);
			}
		} catch (RepositoryException | IOException e) {
			throw new IllegalStateException("Cannot initialise JCR data layer.", e);
		} finally {
			JcrUtils.logoutQuietly(adminSession);
		}
	}

	/** To be overridden. */
	protected Set<String> getWorkspaceNames() {
		return null;
	}

	/**
	 * To be overridden in order to programmatically set relationships between
	 * roles. Does nothing by default.
	 */
	protected void configureStandardRoles() {
	}

	/**
	 * Creates the base JCR tree structure expected for this app if necessary.
	 * 
	 * Expects a clean session ({@link Session#hasPendingChanges()} should return
	 * false) and saves it once the changes have been done. Thus the session can be
	 * rolled back if an exception occurs.
	 * 
	 * @return true if something as been updated
	 */
	public boolean prepareJcrTree(Session adminSession) throws RepositoryException, IOException {
		return false;
	}

	/**
	 * Adds app specific default privileges.
	 * 
	 * Expects a clean session ({@link Session#hasPendingChanges()} should return
	 * false} and saves it once the changes have been done. Thus the session can be
	 * rolled back if an exception occurs.
	 * 
	 * Warning: no check is done and corresponding privileges are always added, so
	 * only call this when necessary
	 */
	public void configurePrivileges(Session session) throws RepositoryException {
	}

	/** The system roles that must be available in the system. */
	protected Set<String> getRequiredRoles() {
		return new HashSet<>();
	}

	public void destroy() {

	}

	/*
	 * UTILITIES
	 */

	/** Create these roles as group if they don't exist. */
	protected void makeSureRolesExists(EnumSet<? extends Distinguished> enumSet) {
		makeSureRolesExists(Distinguished.enumToDns(enumSet));
	}

	/** Create these roles as group if they don't exist. */
	protected void makeSureRolesExists(Set<String> requiredRoles) {
		if (requiredRoles == null)
			return;
		if (getUserAdmin() == null) {
			log.warn("No user admin service available, cannot make sure that role exists");
			return;
		}
		for (String role : requiredRoles) {
			Role systemRole = getUserAdmin().getRole(role);
			if (systemRole == null) {
				try {
					getUserTransaction().begin();
					getUserAdmin().createRole(role, Role.GROUP);
					getUserTransaction().commit();
					log.info("Created role " + role);
				} catch (Exception e) {
					try {
						getUserTransaction().rollback();
					} catch (Exception e1) {
						// silent
					}
					throw new IllegalStateException("Cannot create role " + role, e);
				}
			}
		}
	}

	/** Add a user or group to a group. */
	protected void addToGroup(String groupToAddDn, String groupDn) {
		if (groupToAddDn.contentEquals(groupDn)) {
			if (log.isTraceEnabled())
				log.trace("Ignore adding group " + groupDn + " to itself");
			return;
		}

		if (getUserAdmin() == null) {
			log.warn("No user admin service available, cannot add group " + groupToAddDn + " to " + groupDn);
			return;
		}
		Group groupToAdd = (Group) getUserAdmin().getRole(groupToAddDn);
		if (groupToAdd == null)
			throw new IllegalArgumentException("Group " + groupToAddDn + " not found");
		Group group = (Group) getUserAdmin().getRole(groupDn);
		if (group == null)
			throw new IllegalArgumentException("Group " + groupDn + " not found");
		try {
			getUserTransaction().begin();
			if (group.addMember(groupToAdd))
				log.info("Added " + groupToAddDn + " to " + group);
			getUserTransaction().commit();
		} catch (Exception e) {
			try {
				getUserTransaction().rollback();
			} catch (Exception e1) {
				// silent
			}
			throw new IllegalStateException("Cannot add " + groupToAddDn + " to " + groupDn);
		}
	}

	/*
	 * DEPENDENCY INJECTION
	 */
	public void setRepository(Repository repository) {
		this.repository = repository;
	}

//	public void setUserAdminService(UserAdminService userAdminService) {
//		this.userAdminService = userAdminService;
//	}

	protected WorkTransaction getUserTransaction() {
		return userTransaction;
	}

	protected UserAdmin getUserAdmin() {
		return userAdmin;
	}

	public void setUserAdmin(UserAdmin userAdmin) {
		this.userAdmin = userAdmin;
	}

	public void setUserTransaction(WorkTransaction userTransaction) {
		this.userTransaction = userTransaction;
	}

	public void setContentRepository(ProvidedRepository contentRepository) {
		this.contentRepository = contentRepository;
	}

	protected ProvidedRepository getContentRepository() {
		return contentRepository;
	}


}
