package org.argeo.cms.jcr.internal.servlet;

import java.util.EnumSet;
import java.util.Map;

import javax.jcr.Repository;
import javax.servlet.DispatcherType;
import javax.servlet.http.HttpFilter;

import org.apache.jackrabbit.webdav.simple.SimpleWebdavServlet;
import org.argeo.api.cms.CmsConstants;
import org.argeo.cms.javax.servlet.JavaxAuthFilter;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.URLResourceFactory;

/**
 * Wraps a {@link SimpleWebdavServlet} as a Jetty {@link ServletContextHandler}.
 * The {@link #activate(Map)} method MUST be called before using it.
 */
public class WebDavServletContextHandler extends ServletContextHandler {
	private Repository repository;
	private String alias;

	public WebDavServletContextHandler() {
		// so that we can load configs as resources
		setClassLoader(WebDavServletContextHandler.class.getClassLoader());
		URLResourceFactory resourceFactory = new URLResourceFactory();
		Resource baseResource = resourceFactory.newClassLoaderResource("/org/argeo/cms/jcr/internal/servlet");
		setBaseResource(baseResource);
	}

	public WebDavServletContextHandler(Repository repository, String alias) {
		this();
		this.repository = repository;
		this.alias = alias;
	}

	public void activate(Map<String, String> properties) {
		String contextPath = properties.get(CmsConstants.CONTEXT_PATH);
		if (contextPath.endsWith("/"))
			contextPath = contextPath.substring(0, contextPath.length() - 1);
		setContextPath(contextPath);

		HttpFilter filter = new JavaxAuthFilter();
		FilterHolder filterHolder = new FilterHolder(filter);
		addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST));

		SimpleWebdavServlet webDavServlet = new SimpleWebdavServlet() {

			private static final long serialVersionUID = -5103184475701388404L;

			@Override
			public Repository getRepository() {
				return repository;
			}
		};
		webDavServlet.setSessionProvider(new CmsSessionProvider(alias));
		ServletHolder holder = new ServletHolder(webDavServlet);
		holder.setInitParameters(properties);
		addServlet(holder, "/*");

	}

	public void setRepository(Repository repository, Map<String, String> properties) {
		alias = properties.get(CmsConstants.CN);
		if (alias == null)
			throw new IllegalArgumentException("Only aliased repositories are supported");
		this.repository = repository;
	}

}
