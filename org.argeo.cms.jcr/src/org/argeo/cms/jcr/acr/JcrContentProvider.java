package org.argeo.cms.jcr.acr;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.xml.namespace.NamespaceContext;

import org.argeo.api.acr.Content;
import org.argeo.api.acr.search.BasicSearch;
import org.argeo.api.acr.spi.ContentProvider;
import org.argeo.api.acr.spi.ProvidedContent;
import org.argeo.api.acr.spi.ProvidedSession;
import org.argeo.api.cms.CmsConstants;
import org.argeo.cms.acr.CmsContent;
import org.argeo.cms.jcr.CmsJcrUtils;
import org.argeo.jcr.JcrException;
import org.argeo.jcr.JcrUtils;

/** A JCR workspace accessed as an {@link ContentProvider}. */
public class JcrContentProvider implements ContentProvider, NamespaceContext {

	private Repository jcrRepository;
	private Session adminSession;

	private String mountPath;

	// cache
	private String jcrWorkspace;

	private Map<ProvidedSession, JcrSessionAdapter> sessionAdapters = Collections.synchronizedMap(new HashMap<>());

	public void start(Map<String, String> properties) {
		mountPath = properties.get(CmsConstants.ACR_MOUNT_PATH);
		if ("/".equals(mountPath))
			throw new IllegalArgumentException("JCR content provider cannot be root /");
		Objects.requireNonNull(mountPath);
		jcrWorkspace = CmsContent.getParentPath(mountPath)[1];
		adminSession = CmsJcrUtils.openDataAdminSession(jcrRepository, jcrWorkspace);
	}

	public void stop() {
		if (adminSession.isLive())
			JcrUtils.logoutQuietly(adminSession);
	}

	public void setJcrRepository(Repository jcrRepository) {
		this.jcrRepository = jcrRepository;
	}

	@Override
	public String getMountPath() {
		return mountPath;
	}

	/*
	 * READ
	 */

	@Override
	public ProvidedContent get(ProvidedSession contentSession, String relativePath) {
		return new JcrContent(contentSession, this, jcrWorkspace, toJcrPath(relativePath));
	}

	@Override
	public boolean exists(ProvidedSession contentSession, String relativePath) {
		String jcrPath = '/' + relativePath;
		return new JcrContent(contentSession, this, jcrWorkspace, jcrPath).exists();
	}

	protected JcrSessionAdapter getJcrSessionAdapter(ProvidedSession contentSession) {
		JcrSessionAdapter sessionAdapter = sessionAdapters.get(contentSession);
		if (sessionAdapter == null) {
			final JcrSessionAdapter newSessionAdapter = new JcrSessionAdapter(jcrRepository, contentSession,
					contentSession.getSubject());
			sessionAdapters.put(contentSession, newSessionAdapter);
			contentSession.onClose().thenAccept((s) -> newSessionAdapter.close());
			sessionAdapter = newSessionAdapter;
		}
		return sessionAdapter;
	}

	public Session getJcrSession(ProvidedSession contentSession, String jcrWorkspace) {
		JcrSessionAdapter sessionAdapter = getJcrSessionAdapter(contentSession);
		Session jcrSession = sessionAdapter.getSession(jcrWorkspace);
		return jcrSession;
	}

	public Session getJcrSession(Content content, String jcrWorkspace) {
		return getJcrSession(((ProvidedContent) content).getSession(), jcrWorkspace);
	}

	/*
	 * WRITE
	 */
	Node openForEdit(ProvidedSession contentSession, String jcrWorkspace, String jcrPath) {
		try {
			if (contentSession.isEditing()) {
				JcrSessionAdapter sessionAdapter = getJcrSessionAdapter(contentSession);
				return sessionAdapter.openForEdit(jcrWorkspace, jcrPath);
			} else {
				return getJcrSession(contentSession, jcrWorkspace).getNode(jcrPath);
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot open for edit " + jcrPath + " in workspace " + jcrWorkspace, e);
		}
	}

	@Override
	public void persist(ProvidedSession contentSession) {
		try {
			JcrSessionAdapter sessionAdapter = getJcrSessionAdapter(contentSession);
			sessionAdapter.persist();
		} catch (RepositoryException e) {
			throw new JcrException("Cannot persist " + contentSession, e);
		}
	}

	/*
	 * EDITING
	 */

	@Override
	public void openForEdit(ProvidedSession session, String relativePath) {
		openForEdit(session, relativePath, toJcrPath(relativePath));
	}

	@Override
	public void freeze(ProvidedSession session, String relativePath) {
		try {
			String jcrPath = toJcrPath(relativePath);
			if (session.isEditing()) {
				JcrSessionAdapter sessionAdapter = getJcrSessionAdapter(session);
				sessionAdapter.freeze(jcrWorkspace, jcrPath);
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot freeze " + relativePath + " in workspace " + jcrWorkspace, e);
		}
	}

	@Override
	public boolean isOpenForEdit(ProvidedSession session, String relativePath) {
		try {
			String jcrPath = toJcrPath(relativePath);
			JcrSessionAdapter sessionAdapter = getJcrSessionAdapter(session);
			return sessionAdapter.isOpenForEdit(jcrWorkspace, jcrPath);
		} catch (RepositoryException e) {
			throw new JcrException(
					"Cannot check whether " + relativePath + " is open for edit in workspace " + jcrWorkspace, e);
		}
	}

	/*
	 * NAMESPACE CONTEXT
	 */
	@Override
	public synchronized String getNamespaceURI(String prefix) {
		try {
			return adminSession.getNamespaceURI(prefix);
		} catch (RepositoryException e) {
			throw new JcrException(e);
		}
	}

	@Override
	public synchronized String getPrefix(String namespaceURI) {
		try {
			return adminSession.getNamespacePrefix(namespaceURI);
		} catch (RepositoryException e) {
			throw new JcrException(e);
		}
	}

	@Override
	public synchronized Iterator<String> getPrefixes(String namespaceURI) {
		try {
			return Arrays.asList(adminSession.getNamespacePrefix(namespaceURI)).iterator();
		} catch (RepositoryException e) {
			throw new JcrException(e);
		}
	}

	/*
	 * SEARCH
	 */

	@Override
	public Spliterator<Content> search(ProvidedSession session, BasicSearch search, String relPath) {
		try {
			Session jcrSession = getJcrSession(session, jcrWorkspace);
			BasicSearchToQom jcrBasicSearch = new BasicSearchToQom(jcrSession, search, relPath);
			Query query = jcrBasicSearch.createQuery();
			QueryResult queryResult = query.execute();
			return new QueryResultSpliterator(session, queryResult.getNodes());
		} catch (RepositoryException e) {
			throw new JcrException(e);
		}
	}

	class QueryResultSpliterator implements Spliterator<Content> {
		private ProvidedSession providedSession;
		private NodeIterator nodeIterator;

		public QueryResultSpliterator(ProvidedSession providedSession, NodeIterator nodeIterator) {
			super();
			this.providedSession = providedSession;
			this.nodeIterator = nodeIterator;
		}

		@Override
		public boolean tryAdvance(Consumer<? super Content> action) {
			if (!nodeIterator.hasNext())
				return false;
			try {
				Node node = nodeIterator.nextNode();
				// TODO optimise by reusing the Node
				JcrContent jcrContent = new JcrContent(providedSession, JcrContentProvider.this, jcrWorkspace,
						node.getPath());
				action.accept(jcrContent);
				return true;
			} catch (RepositoryException e) {
				throw new JcrException(e);
			}
		}

		@Override
		public Spliterator<Content> trySplit() {
			return null;
		}

		@Override
		public long estimateSize() {
			return nodeIterator.getSize();
		}

		@Override
		public int characteristics() {
			return NONNULL | SIZED;
		}

	}

	/*
	 * UTILITIES
	 */
	/**
	 * Just adds a '/' so that it becomes an absolute JCR path within the JCR
	 * workspace of this provider.
	 */
	private String toJcrPath(String relativePath) {
		return '/' + relativePath;
	}

	/*
	 * TRANSITIONAL, WHILE MIGRATING FROM JCR TO ACR
	 */
	@Deprecated
	public synchronized <T> T doInAdminSession(Function<Session, T> toDo) {
		try {
			return toDo.apply(adminSession);
		} finally {
			try {
				if (adminSession.hasPendingChanges())
					adminSession.save();
			} catch (RepositoryException e) {
				throw new JcrException("Cannot save admin session", e);
			}
		}
	}

}
