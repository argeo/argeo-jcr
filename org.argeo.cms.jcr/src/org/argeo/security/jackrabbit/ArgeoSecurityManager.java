package org.argeo.security.jackrabbit;

import java.security.Principal;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import javax.jcr.Credentials;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.x500.X500Principal;

import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.core.DefaultSecurityManager;
import org.apache.jackrabbit.core.security.AMContext;
import org.apache.jackrabbit.core.security.AccessManager;
import org.apache.jackrabbit.core.security.SecurityConstants;
import org.apache.jackrabbit.core.security.SystemPrincipal;
import org.apache.jackrabbit.core.security.authentication.AuthContext;
import org.apache.jackrabbit.core.security.authentication.CallbackHandlerImpl;
import org.apache.jackrabbit.core.security.authorization.WorkspaceAccessManager;
import org.apache.jackrabbit.core.security.principal.AdminPrincipal;
import org.apache.jackrabbit.core.security.principal.PrincipalProvider;
import org.argeo.api.cms.AnonymousPrincipal;
import org.argeo.api.cms.CmsConstants;
import org.argeo.api.cms.CmsLog;
import org.argeo.api.cms.DataAdminPrincipal;

/** Customises Jackrabbit security. */
public class ArgeoSecurityManager extends DefaultSecurityManager {
	private final static CmsLog log = CmsLog.getLog(ArgeoSecurityManager.class);

//	private BundleContext cmsBundleContext = null;

	public ArgeoSecurityManager() {
//		if (FrameworkUtil.getBundle(CmsSession.class) != null) {
//			cmsBundleContext = FrameworkUtil.getBundle(CmsSession.class).getBundleContext();
//		}
	}

	public AuthContext getAuthContext(Credentials creds, Subject subject, String workspaceName)
			throws RepositoryException {
		checkInitialized();

		CallbackHandler cbHandler = new CallbackHandlerImpl(creds, getSystemSession(), getPrincipalProviderRegistry(),
				adminId, anonymousId);
		String appName = "Jackrabbit";
		return new ArgeoAuthContext(appName, subject, cbHandler);
	}

	@Override
	public AccessManager getAccessManager(Session session, AMContext amContext) throws RepositoryException {
		synchronized (getSystemSession()) {
			return super.getAccessManager(session, amContext);
		}
	}

	@Override
	public UserManager getUserManager(Session session) throws RepositoryException {
		synchronized (getSystemSession()) {
			return super.getUserManager(session);
		}
	}

	@Override
	protected PrincipalProvider createDefaultPrincipalProvider(Properties[] moduleConfig) throws RepositoryException {
		return super.createDefaultPrincipalProvider(moduleConfig);
	}

	/** Called once when the session is created */
	@Override
	public String getUserID(Subject subject, String workspaceName) throws RepositoryException {
		boolean isAnonymous = !subject.getPrincipals(AnonymousPrincipal.class).isEmpty();
		boolean isDataAdmin = !subject.getPrincipals(DataAdminPrincipal.class).isEmpty();
		boolean isJackrabbitSystem = !subject.getPrincipals(SystemPrincipal.class).isEmpty();
		Set<X500Principal> userPrincipal = subject.getPrincipals(X500Principal.class);
		boolean isRegularUser = !userPrincipal.isEmpty();
//		CmsSession cmsSession = null;
//		if (cmsBundleContext != null) {
//			cmsSession = CmsOsgiUtils.getCmsSession(cmsBundleContext, subject);
//			if (log.isTraceEnabled())
//				log.trace("Opening JCR session for CMS session " + cmsSession);
//		}

		if (isAnonymous) {
			if (isDataAdmin || isJackrabbitSystem || isRegularUser)
				throw new IllegalStateException("Inconsistent " + subject);
			else
				return CmsConstants.ROLE_ANONYMOUS;
		} else if (isRegularUser) {// must be before DataAdmin
			if (isAnonymous || isJackrabbitSystem)
				throw new IllegalStateException("Inconsistent " + subject);
			else {
				if (userPrincipal.size() > 1) {
					StringBuilder buf = new StringBuilder();
					for (X500Principal principal : userPrincipal)
						buf.append(' ').append('\"').append(principal).append('\"');
					throw new RuntimeException("Multiple user principals:" + buf);
				}
				return userPrincipal.iterator().next().getName();
			}
		} else if (isDataAdmin) {
			if (isAnonymous || isJackrabbitSystem || isRegularUser)
				throw new IllegalStateException("Inconsistent " + subject);
			else {
				assert !subject.getPrincipals(AdminPrincipal.class).isEmpty();
				return CmsConstants.ROLE_DATA_ADMIN;
			}
		} else if (isJackrabbitSystem) {
			if (isAnonymous || isDataAdmin || isRegularUser)
				throw new IllegalStateException("Inconsistent " + subject);
			else
				return super.getUserID(subject, workspaceName);
		} else {
			throw new IllegalStateException("Unrecognized subject type: " + subject);
		}
	}

	@Override
	protected WorkspaceAccessManager createDefaultWorkspaceAccessManager() {
		WorkspaceAccessManager wam = super.createDefaultWorkspaceAccessManager();
		ArgeoWorkspaceAccessManagerImpl workspaceAccessManager = new ArgeoWorkspaceAccessManagerImpl(wam);
		if (log.isTraceEnabled())
			log.trace("Created workspace access manager");
		return workspaceAccessManager;
	}

	private class ArgeoWorkspaceAccessManagerImpl implements SecurityConstants, WorkspaceAccessManager {
		private final WorkspaceAccessManager wam;

		public ArgeoWorkspaceAccessManagerImpl(WorkspaceAccessManager wam) {
			super();
			this.wam = wam;
		}

		public void init(Session systemSession) throws RepositoryException {
			wam.init(systemSession);
			Repository repository = systemSession.getRepository();
			if (log.isTraceEnabled())
				log.trace("Initialised workspace access manager on repository " + repository
						+ ", systemSession workspace: " + systemSession.getWorkspace().getName());
		}

		public void close() throws RepositoryException {
		}

		public boolean grants(Set<Principal> principals, String workspaceName) throws RepositoryException {
			// TODO: implements finer access to workspaces
			if (log.isTraceEnabled())
				log.trace("Grants " + new HashSet<>(principals) + " access to workspace '" + workspaceName + "'");
			return true;
			// return wam.grants(principals, workspaceName);
		}
	}

}
