package org.argeo.cms.jcr.acr;

import org.argeo.api.acr.QNamed;

public enum JcrName implements QNamed {
	created, lastModified, isCheckedOut;

	@Override
	public String getNamespace() {
		return JcrContentNamespace.JCR.getNamespaceURI();
	}

	@Override
	public String getDefaultPrefix() {
		return JcrContentNamespace.JCR.getDefaultPrefix();
	}

}
