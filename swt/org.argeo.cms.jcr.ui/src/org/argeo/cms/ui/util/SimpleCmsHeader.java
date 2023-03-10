package org.argeo.cms.ui.util;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.argeo.cms.swt.CmsException;
import org.argeo.cms.swt.CmsStyles;
import org.argeo.cms.swt.CmsSwtUtils;
import org.argeo.cms.ui.CmsUiProvider;
import org.eclipse.rap.rwt.RWT;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/** A header in three parts */
public class SimpleCmsHeader implements CmsUiProvider {
	private List<CmsUiProvider> lead = new ArrayList<CmsUiProvider>();
	private List<CmsUiProvider> center = new ArrayList<CmsUiProvider>();
	private List<CmsUiProvider> end = new ArrayList<CmsUiProvider>();

	private Boolean subPartsSameWidth = false;

	@Override
	public Control createUi(Composite parent, Node context) throws RepositoryException {
		Composite header = new Composite(parent, SWT.NONE);
		header.setData(RWT.CUSTOM_VARIANT, CmsStyles.CMS_HEADER);
		header.setBackgroundMode(SWT.INHERIT_DEFAULT);
		header.setLayout(CmsSwtUtils.noSpaceGridLayout(new GridLayout(3, false)));

		configurePart(context, header, lead);
		configurePart(context, header, center);
		configurePart(context, header, end);
		return header;
	}

	protected void configurePart(Node context, Composite parent, List<CmsUiProvider> partProviders)
			throws RepositoryException {
		final int style;
		final String custom;
		if (lead == partProviders) {
			style = SWT.LEAD;
			custom = CmsStyles.CMS_HEADER_LEAD;
		} else if (center == partProviders) {
			style = SWT.CENTER;
			custom = CmsStyles.CMS_HEADER_CENTER;
		} else if (end == partProviders) {
			style = SWT.END;
			custom = CmsStyles.CMS_HEADER_END;
		} else {
			throw new CmsException("Unsupported part providers " + partProviders);
		}

		Composite part = new Composite(parent, SWT.NONE);
		part.setData(RWT.CUSTOM_VARIANT, custom);
		GridData gridData = new GridData(style, SWT.FILL, true, true);
		part.setLayoutData(gridData);
		part.setLayout(CmsSwtUtils.noSpaceGridLayout(new GridLayout(partProviders.size(), subPartsSameWidth)));
		for (CmsUiProvider uiProvider : partProviders) {
			Control subPart = uiProvider.createUi(part, context);
			subPart.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		}
	}

	public void setLead(List<CmsUiProvider> lead) {
		this.lead = lead;
	}

	public void setCenter(List<CmsUiProvider> center) {
		this.center = center;
	}

	public void setEnd(List<CmsUiProvider> end) {
		this.end = end;
	}

	public void setSubPartsSameWidth(Boolean subPartsSameWidth) {
		this.subPartsSameWidth = subPartsSameWidth;
	}

	public List<CmsUiProvider> getLead() {
		return lead;
	}

	public List<CmsUiProvider> getCenter() {
		return center;
	}

	public List<CmsUiProvider> getEnd() {
		return end;
	}

}
