package org.argeo.cms.jcr.acr;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;

import org.argeo.api.acr.spi.ContentNamespace;

/** JCR-specific content namespaces. */
public enum JcrContentNamespace implements ContentNamespace {
	//
	// ARGEO
	//
	JCRX("jcrx", "http://www.argeo.org/ns/jcrx", null, null),
	//
	// EXTERNAL
	//
	JCR("jcr", "http://www.jcp.org/jcr/1.0", null,
			"https://jackrabbit.apache.org/archive/wiki/JCR/NamespaceRegistry_115513459.html"),
	//
	JCR_MIX("mix", "http://www.jcp.org/jcr/mix/1.0", null,
			"https://jackrabbit.apache.org/archive/wiki/JCR/NamespaceRegistry_115513459.html"),
	//
	JCR_NT("nt", "http://www.jcp.org/jcr/nt/1.0", null,
			"https://jackrabbit.apache.org/archive/wiki/JCR/NamespaceRegistry_115513459.html"),
	//
	JACKRABBIT("rep", "internal", null,
			"https://jackrabbit.apache.org/archive/wiki/JCR/NamespaceRegistry_115513459.html"),
	//
	;

	private final static String RESOURCE_BASE = "/org/argeo/cms/jcr/acr/schemas/";

	private String defaultPrefix;
	private String namespace;
	private URL resource;
	private URL publicUrl;

	JcrContentNamespace(String defaultPrefix, String namespace, String resourceFileName, String publicUrl) {
		Objects.requireNonNull(namespace);
		this.defaultPrefix = defaultPrefix;
		Objects.requireNonNull(namespace);
		this.namespace = namespace;
		if (resourceFileName != null) {
			resource = getClass().getResource(RESOURCE_BASE + resourceFileName);
			Objects.requireNonNull(resource);
		}
		if (publicUrl != null)
			try {
				this.publicUrl = new URL(publicUrl);
			} catch (MalformedURLException e) {
				throw new IllegalArgumentException("Cannot interpret public URL", e);
			}
	}

	@Override
	public String getDefaultPrefix() {
		return defaultPrefix;
	}

	@Override
	public String getNamespaceURI() {
		return namespace;
	}

	@Override
	public URL getSchemaResource() {
		return resource;
	}

	public URL getPublicUrl() {
		return publicUrl;
	}

}
