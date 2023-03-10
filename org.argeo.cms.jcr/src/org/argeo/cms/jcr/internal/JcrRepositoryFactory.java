package org.argeo.cms.jcr.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.RepositoryFactory;

import org.apache.jackrabbit.jcr2dav.Jcr2davRepositoryFactory;
import org.argeo.api.cms.CmsConstants;
import org.argeo.api.cms.CmsLog;
import org.argeo.cms.internal.jcr.RepoConf;
import org.argeo.cms.jcr.internal.osgi.CmsJcrActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

/**
 * OSGi-aware Jackrabbit repository factory which can retrieve/publish
 * {@link Repository} as OSGi services.
 */
public class JcrRepositoryFactory implements RepositoryFactory {
	private final CmsLog log = CmsLog.getLog(getClass());
//	private final BundleContext bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();

	// private Resource fileRepositoryConfiguration = new ClassPathResource(
	// "/org/argeo/cms/internal/kernel/repository-localfs.xml");

	protected Repository getRepositoryByAlias(String alias) {
		BundleContext bundleContext = CmsJcrActivator.getBundleContext();
		if (bundleContext != null) {
			try {
				Collection<ServiceReference<Repository>> srs = bundleContext.getServiceReferences(Repository.class,
						"(" + CmsConstants.CN + "=" + alias + ")");
				if (srs.size() == 0)
					throw new IllegalArgumentException("No repository with alias " + alias + " found in OSGi registry");
				else if (srs.size() > 1)
					throw new IllegalArgumentException(
							srs.size() + " repositories with alias " + alias + " found in OSGi registry");
				return bundleContext.getService(srs.iterator().next());
			} catch (InvalidSyntaxException e) {
				throw new IllegalArgumentException("Cannot find repository with alias " + alias, e);
			}
		} else {
			// TODO ability to filter static services
			return null;
		}
	}

	// private void publish(String alias, Repository repository, Properties
	// properties) {
	// if (bundleContext != null) {
	// // do not modify reference
	// Hashtable<String, String> props = new Hashtable<String, String>();
	// props.putAll(props);
	// props.put(JCR_REPOSITORY_ALIAS, alias);
	// bundleContext.registerService(Repository.class.getName(), repository,
	// props);
	// }
	// }

	@SuppressWarnings({ "rawtypes" })
	public Repository getRepository(Map parameters) throws RepositoryException {
		// // check if can be found by alias
		// Repository repository = super.getRepository(parameters);
		// if (repository != null)
		// return repository;

		// check if remote
		Repository repository;
		String uri = null;
		if (parameters.containsKey(RepoConf.labeledUri.name()))
			uri = parameters.get(CmsConstants.LABELED_URI).toString();
		else if (parameters.containsKey(KernelConstants.JACKRABBIT_REPOSITORY_URI))
			uri = parameters.get(KernelConstants.JACKRABBIT_REPOSITORY_URI).toString();

		if (uri != null) {
			if (uri.startsWith("http")) {// http, https
				Object defaultWorkspace = parameters.get(RepoConf.defaultWorkspace.name());
				repository = createRemoteRepository(uri, defaultWorkspace != null ? defaultWorkspace.toString() : null);
			} else if (uri.startsWith("file"))// http, https
				repository = createFileRepository(uri, parameters);
			else if (uri.startsWith("vm")) {
				// log.warn("URI " + uri + " should have been managed by generic
				// JCR repository factory");
				repository = getRepositoryByAlias(getAliasFromURI(uri));
			} else
				throw new IllegalArgumentException("Unrecognized URI format " + uri);

		}

		else if (parameters.containsKey(CmsConstants.CN)) {
			// Properties properties = new Properties();
			// properties.putAll(parameters);
			String alias = parameters.get(CmsConstants.CN).toString();
			// publish(alias, repository, properties);
			// log.info("Registered JCR repository under alias '" + alias + "'
			// with properties " + properties);
			repository = getRepositoryByAlias(alias);
		} else
			throw new IllegalArgumentException("Not enough information in " + parameters);

		if (repository == null)
			throw new IllegalArgumentException("Repository not found " + parameters);

		return repository;
	}

	protected Repository createRemoteRepository(String uri, String defaultWorkspace) throws RepositoryException {
		Map<String, String> params = new HashMap<String, String>();
		params.put(KernelConstants.JACKRABBIT_REPOSITORY_URI, uri);
		if (defaultWorkspace != null)
			params.put(KernelConstants.JACKRABBIT_REMOTE_DEFAULT_WORKSPACE, defaultWorkspace);
		Repository repository = new Jcr2davRepositoryFactory().getRepository(params);
		if (repository == null)
			throw new IllegalArgumentException("Remote Davex repository " + uri + " not found");
		log.info("Initialized remote Jackrabbit repository from uri " + uri);
		return repository;
	}

	@SuppressWarnings({ "rawtypes" })
	protected Repository createFileRepository(final String uri, Map parameters) throws RepositoryException {
		throw new UnsupportedOperationException();
		// InputStream configurationIn = null;
		// try {
		// Properties vars = new Properties();
		// vars.putAll(parameters);
		// String dirPath = uri.substring("file:".length());
		// File homeDir = new File(dirPath);
		// if (homeDir.exists() && !homeDir.isDirectory())
		// throw new ArgeoJcrException("Repository home " + dirPath + " is not a
		// directory");
		// if (!homeDir.exists())
		// homeDir.mkdirs();
		// configurationIn = fileRepositoryConfiguration.getInputStream();
		// vars.put(RepositoryConfigurationParser.REPOSITORY_HOME_VARIABLE,
		// homeDir.getCanonicalPath());
		// RepositoryConfig repositoryConfig = RepositoryConfig.create(new
		// InputSource(configurationIn), vars);
		//
		// // TransientRepository repository = new
		// // TransientRepository(repositoryConfig);
		// final RepositoryImpl repository =
		// RepositoryImpl.create(repositoryConfig);
		// Session session = repository.login();
		// // FIXME make it generic
		// org.argeo.jcr.JcrUtils.addPrivilege(session, "/", "ROLE_ADMIN",
		// "jcr:all");
		// org.argeo.jcr.JcrUtils.logoutQuietly(session);
		// Runtime.getRuntime().addShutdownHook(new Thread("Clean JCR repository
		// " + uri) {
		// public void run() {
		// repository.shutdown();
		// log.info("Destroyed repository " + uri);
		// }
		// });
		// log.info("Initialized file Jackrabbit repository from uri " + uri);
		// return repository;
		// } catch (Exception e) {
		// throw new ArgeoJcrException("Cannot create repository " + uri, e);
		// } finally {
		// IOUtils.closeQuietly(configurationIn);
		// }
	}

	protected String getAliasFromURI(String uri) {
		try {
			URI uriObj = new URI(uri);
			String alias = uriObj.getPath();
			if (alias.charAt(0) == '/')
				alias = alias.substring(1);
			if (alias.charAt(alias.length() - 1) == '/')
				alias = alias.substring(0, alias.length() - 1);
			return alias;
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Cannot interpret URI " + uri, e);
		}
	}

	/**
	 * Called after the repository has been initialised. Does nothing by default.
	 */
	@SuppressWarnings("rawtypes")
	protected void postInitialization(Repository repository, Map parameters) {

	}
}
