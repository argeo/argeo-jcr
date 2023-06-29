package org.argeo.cms.jcr.acr;

import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;
import javax.jcr.version.VersionManager;
import javax.security.auth.Subject;

import org.apache.jackrabbit.core.SessionImpl;
import org.argeo.api.acr.spi.ProvidedSession;
import org.argeo.jcr.Jcr;
import org.argeo.jcr.JcrException;
import org.argeo.jcr.JcrUtils;

/** Manages JCR {@link Session} in an ACR context. */
class JcrSessionAdapter {
	private Repository repository;
	private Subject subject;

	private ProvidedSession contentSession;

	private Map<Thread, Map<String, Session>> threadSessions = Collections.synchronizedMap(new HashMap<>());

	private boolean closed = false;

	private Thread lastRetrievingThread = null;

//	private Thread writeThread;
	private Map<String, Session> writeSessions = new HashMap<>();
	/**
	 * Path of versionable nodes which have been modified during an edition cycle.
	 */
	private Map<String, Set<String>> checkedInModified = new HashMap<>();
	private Map<String, Set<String>> checkedOutModified = new HashMap<>();

	public JcrSessionAdapter(Repository repository, ProvidedSession contentSession, Subject subject) {
		this.repository = repository;
		this.contentSession = contentSession;
		this.subject = subject;
	}

	public synchronized void close() {
		for (Map<String, Session> sessions : threadSessions.values()) {
			for (Session session : sessions.values()) {
				JcrUtils.logoutQuietly(session);
			}
			sessions.clear();
		}
		threadSessions.clear();
		closed = true;
	}

	public synchronized Session getSession(String workspace) {
		if (closed)
			throw new IllegalStateException("JCR session adapter is closed.");

		Thread currentThread = Thread.currentThread();
		if (lastRetrievingThread == null)
			lastRetrievingThread = currentThread;

		Map<String, Session> threadSession = threadSessions.get(currentThread);
		if (threadSession == null) {
			threadSession = new HashMap<>();
			threadSessions.put(currentThread, threadSession);
		}

		Session session = threadSession.get(workspace);
		if (session == null) {
			session = login(workspace);
			threadSession.put(workspace, session);
		}

		if (lastRetrievingThread != currentThread) {
			try {
				session.refresh(true);
			} catch (RepositoryException e) {
				throw new JcrException("Cannot refresh JCR session " + session, e);
			}
		}
		lastRetrievingThread = currentThread;
		return session;
	}

	protected synchronized Session getWriteSession(String workspace) throws RepositoryException {
		Session session = writeSessions.get(workspace);
		if (session == null) {
			session = login(workspace);
			writeSessions.put(workspace, session);
		} else {
//			if ((writeThread != Thread.currentThread()) && session.hasPendingChanges()) {
//				throw new IllegalStateException("Session " + contentSession + " is currently being written to");
//			}
//			writeThread = Thread.currentThread();
		}
		return session;
	}

	public synchronized Node openForEdit(String workspace, String jcrPath) throws RepositoryException {
		Session session = getWriteSession(workspace);
		Node node = session.getNode(jcrPath);
		if (node.isNodeType(NodeType.MIX_SIMPLE_VERSIONABLE)) {
			VersionManager versionManager = session.getWorkspace().getVersionManager();
			if (versionManager.isCheckedOut(jcrPath)) {
				if (!checkedOutModified.containsKey(workspace))
					checkedOutModified.put(workspace, new TreeSet<>());
				checkedOutModified.get(workspace).add(jcrPath);
			} else {
				if (!checkedInModified.containsKey(workspace))
					checkedInModified.put(workspace, new TreeSet<>());
				checkedInModified.get(workspace).add(jcrPath);
				versionManager.checkout(jcrPath);
			}
		}
		return node;
	}

	public synchronized Node freeze(String workspace, String jcrPath) throws RepositoryException {
		Session session = getWriteSession(workspace);
		Node node = session.getNode(jcrPath);
		if (node.isNodeType(NodeType.MIX_SIMPLE_VERSIONABLE)) {
			VersionManager versionManager = session.getWorkspace().getVersionManager();
			if (versionManager.isCheckedOut(jcrPath)) {
				versionManager.checkin(jcrPath);
			}
		}
		return node;
	}

	public synchronized boolean isOpenForEdit(String workspace, String jcrPath) throws RepositoryException {
		Session session = getWriteSession(workspace);
		VersionManager versionManager = session.getWorkspace().getVersionManager();
		return versionManager.isCheckedOut(jcrPath);
	}

	public synchronized void persist() throws RepositoryException {
		for (String workspace : writeSessions.keySet()) {
			Session session = writeSessions.get(workspace);
			if (session == null) {
//				assert writeThread == null;
				assert !checkedOutModified.containsKey(workspace);
				assert !checkedInModified.containsKey(workspace);
				return; // nothing to do
			}
			session.save();
			VersionManager versionManager = session.getWorkspace().getVersionManager();
			if (checkedOutModified.containsKey(workspace))
				for (String jcrPath : checkedOutModified.get(workspace)) {
					versionManager.checkpoint(jcrPath);
				}
			if (checkedInModified.containsKey(workspace))
				for (String jcrPath : checkedInModified.get(workspace)) {
					versionManager.checkin(jcrPath);
				}
			Jcr.logout(session);
		}

		for (Map<String, Session> m : threadSessions.values())
			for (Session session : m.values())
				session.refresh(true);
//			writeThread = null;
		writeSessions.clear();
		checkedOutModified.clear();
		checkedInModified.clear();
	}

	protected Session login(String workspace) {
		return Subject.doAs(subject, (PrivilegedAction<Session>) () -> {
			try {
//				String username = CurrentUser.getUsername(subject);
//				SimpleCredentials credentials = new SimpleCredentials(username, new char[0]);
//				credentials.setAttribute(ProvidedSession.class.getName(), contentSession);
				Session sess = repository.login(workspace);
				// Jackrabbit specific:
				((SessionImpl) sess).setAttribute(ProvidedSession.class.getName(), contentSession);
				return sess;
			} catch (RepositoryException e) {
				throw new IllegalStateException("Cannot log in to " + workspace, e);
			}
		});
	}

}
