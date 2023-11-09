package org.argeo.cms.jcr.acr;

import org.argeo.api.acr.QNamed;

public enum MixInType implements QNamed {
	mimeType;

	@Override
	public String getNamespace() {
		return JcrContentNamespace.JCR_MIX.getNamespaceURI();
	}

	@Override
	public String getDefaultPrefix() {
		return JcrContentNamespace.JCR_MIX.getDefaultPrefix();
	}
}
