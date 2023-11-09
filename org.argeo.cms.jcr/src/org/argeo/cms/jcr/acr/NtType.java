package org.argeo.cms.jcr.acr;

import org.argeo.api.acr.QNamed;

public enum NtType implements QNamed {
	file, folder;

	@Override
	public String getNamespace() {
		return JcrContentNamespace.JCR_NT.getNamespaceURI();
	}

	@Override
	public String getDefaultPrefix() {
		return JcrContentNamespace.JCR_NT.getDefaultPrefix();
	}
}
