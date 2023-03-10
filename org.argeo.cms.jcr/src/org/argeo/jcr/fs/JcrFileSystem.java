package org.argeo.jcr.fs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.jcr.Credentials;
import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.argeo.api.acr.fs.AbstractFsStore;
import org.argeo.api.acr.fs.AbstractFsSystem;
import org.argeo.jcr.Jcr;
import org.argeo.jcr.JcrUtils;

public class JcrFileSystem extends AbstractFsSystem<WorkspaceFileStore> {
	private final JcrFileSystemProvider provider;

	private final Repository repository;
	private Session session;
	private WorkspaceFileStore baseFileStore;

	private Map<String, WorkspaceFileStore> mounts = new TreeMap<>();

	private String userHomePath = null;

	@Deprecated
	public JcrFileSystem(JcrFileSystemProvider provider, Session session) throws IOException {
		super();
		this.provider = provider;
		baseFileStore = new WorkspaceFileStore(null, session.getWorkspace());
		this.session = session;
//		Node userHome = provider.getUserHome(session);
//		if (userHome != null)
//			try {
//				userHomePath = userHome.getPath();
//			} catch (RepositoryException e) {
//				throw new IOException("Cannot retrieve user home path", e);
//			}
		this.repository = null;
	}

	public JcrFileSystem(JcrFileSystemProvider provider, Repository repository) throws IOException {
		this(provider, repository, null);
	}

	public JcrFileSystem(JcrFileSystemProvider provider, Repository repository, Credentials credentials)
			throws IOException {
		super();
		this.provider = provider;
		this.repository = repository;
		try {
			this.session = credentials == null ? repository.login() : repository.login(credentials);
			baseFileStore = new WorkspaceFileStore(null, session.getWorkspace());
			workspaces: for (String workspaceName : baseFileStore.getWorkspace().getAccessibleWorkspaceNames()) {
				if (workspaceName.equals(baseFileStore.getWorkspace().getName()))
					continue workspaces;// do not mount base
				if (workspaceName.equals("security")) {
					continue workspaces;// do not mount security workspace
					// TODO make it configurable
				}
				Session mountSession = credentials == null ? repository.login(workspaceName)
						: repository.login(credentials, workspaceName);
				String mountPath = JcrPath.separator + workspaceName;
				mounts.put(mountPath, new WorkspaceFileStore(mountPath, mountSession.getWorkspace()));
			}
		} catch (RepositoryException e) {
			throw new IOException("Cannot initialise file system", e);
		}

		Node userHome = provider.getUserHome(repository);
		if (userHome != null)
			try {
				userHomePath = toFsPath(userHome);
			} catch (RepositoryException e) {
				throw new IOException("Cannot retrieve user home path", e);
			} finally {
				JcrUtils.logoutQuietly(Jcr.session(userHome));
			}
	}

	public String toFsPath(Node node) throws RepositoryException {
		return getFileStore(node).toFsPath(node);
	}

	/** Whether this node should be skipped in directory listings */
	public boolean skipNode(Node node) throws RepositoryException {
		if (node.isNodeType(NodeType.NT_HIERARCHY_NODE))
			return false;
		return true;
	}

	public String getUserHomePath() {
		return userHomePath;
	}

	public WorkspaceFileStore getFileStore(String path) {
		WorkspaceFileStore res = baseFileStore;
		for (String mountPath : mounts.keySet()) {
			if (path.equals(mountPath))
				return mounts.get(mountPath);
			if (path.startsWith(mountPath + JcrPath.separator)) {
				res = mounts.get(mountPath);
				// we keep the last one
			}
		}
		assert res != null;
		return res;
	}

	public WorkspaceFileStore getFileStore(Node node) throws RepositoryException {
		String workspaceName = node.getSession().getWorkspace().getName();
		if (workspaceName.equals(baseFileStore.getWorkspace().getName()))
			return baseFileStore;
		for (String mountPath : mounts.keySet()) {
			WorkspaceFileStore fileStore = mounts.get(mountPath);
			if (workspaceName.equals(fileStore.getWorkspace().getName()))
				return fileStore;
		}
		throw new IllegalStateException("No workspace mount found for " + node + " in workspace " + workspaceName);
	}

	public Iterator<JcrPath> listDirectMounts(Path base) {
		String baseStr = base.toString();
		Set<JcrPath> res = new HashSet<>();
		mounts: for (String mountPath : mounts.keySet()) {
			if (mountPath.equals(baseStr))
				continue mounts;
			if (mountPath.startsWith(baseStr)) {
				JcrPath path = new JcrPath(this, mountPath);
				Path relPath = base.relativize(path);
				if (relPath.getNameCount() == 1)
					res.add(path);
			}
		}
		return res.iterator();
	}

	public WorkspaceFileStore getBaseFileStore() {
		return baseFileStore;
	}

	@Override
	public FileSystemProvider provider() {
		return provider;
	}

	@Override
	public void close() throws IOException {
		JcrUtils.logoutQuietly(session);
		for (String mountPath : mounts.keySet()) {
			WorkspaceFileStore fileStore = mounts.get(mountPath);
			try {
				fileStore.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public boolean isOpen() {
		return session.isLive();
	}

	@Override
	public boolean isReadOnly() {
		return false;
	}

	@Override
	public String getSeparator() {
		return JcrPath.separator;
	}

	@Override
	public Iterable<Path> getRootDirectories() {
		Set<Path> single = new HashSet<>();
		single.add(new JcrPath(this, JcrPath.separator));
		return single;
	}

	@Override
	public Iterable<FileStore> getFileStores() {
		List<FileStore> stores = new ArrayList<>();
		stores.add(baseFileStore);
		stores.addAll(mounts.values());
		return stores;
	}

	@Override
	public Set<String> supportedFileAttributeViews() {
		try {
			String[] prefixes = session.getNamespacePrefixes();
			Set<String> res = new HashSet<>();
			for (String prefix : prefixes)
				res.add(prefix);
			res.add("basic");
			return res;
		} catch (RepositoryException e) {
			throw new JcrFsException("Cannot get supported file attributes views", e);
		}
	}

	@Override
	public Path getPath(String first, String... more) {
		StringBuilder sb = new StringBuilder(first);
		// TODO Make it more robust
		for (String part : more)
			sb.append('/').append(part);
		return new JcrPath(this, sb.toString());
	}

	@Override
	public PathMatcher getPathMatcher(String syntaxAndPattern) {
		throw new UnsupportedOperationException();
	}

	@Override
	public UserPrincipalLookupService getUserPrincipalLookupService() {
		throw new UnsupportedOperationException();
	}

	@Override
	public WatchService newWatchService() throws IOException {
		throw new UnsupportedOperationException();
	}

//	public Session getSession() {
//		return session;
//	}

	public Repository getRepository() {
		return repository;
	}

}
