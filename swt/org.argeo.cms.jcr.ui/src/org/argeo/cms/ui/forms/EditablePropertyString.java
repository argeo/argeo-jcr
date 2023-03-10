package org.argeo.cms.ui.forms;

import static org.argeo.cms.ui.forms.FormStyle.propertyMessage;
import static org.argeo.cms.ui.forms.FormStyle.propertyText;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.argeo.cms.swt.CmsSwtUtils;
import org.argeo.cms.swt.SwtEditablePart;
import org.argeo.cms.ui.widgets.EditableText;
import org.argeo.eclipse.ui.EclipseUiUtils;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/** Editable String in a CMS context */
public class EditablePropertyString extends EditableText implements SwtEditablePart {
	private static final long serialVersionUID = 5055000749992803591L;

	private String propertyName;
	private String message;

	// encode the '&' character in rap
	private final static String AMPERSAND = "&#38;";
	private final static String AMPERSAND_REGEX = "&(?![#a-zA-Z0-9]+;)";

	public EditablePropertyString(Composite parent, int style, Node node, String propertyName, String message)
			throws RepositoryException {
		super(parent, style, node, true);
		//setUseTextAsLabel(true);
		this.propertyName = propertyName;
		this.message = message;

		if (node.hasProperty(propertyName)) {
			this.setStyle(propertyText.style());
			this.setText(node.getProperty(propertyName).getString());
		} else {
			this.setStyle(propertyMessage.style());
			this.setText(message + "  ");
		}
	}

	public void setText(String text) {
		Control child = getControl();
		if (child instanceof Label) {
			Label lbl = (Label) child;
			if (EclipseUiUtils.isEmpty(text))
				lbl.setText(message + "  ");
			else
				// TODO enhance this
				lbl.setText(text.replaceAll(AMPERSAND_REGEX, AMPERSAND));
		} else if (child instanceof Text) {
			Text txt = (Text) child;
			if (EclipseUiUtils.isEmpty(text)) {
				txt.setText("");
				txt.setMessage(message + " ");
			} else
				txt.setText(text.replaceAll("<br/>", "\n"));
		}
	}

	public synchronized void startEditing() {
		CmsSwtUtils.style(getControl(), FormStyle.propertyText);
		super.startEditing();
	}

	public synchronized void stopEditing() {
		if (EclipseUiUtils.isEmpty(((Text) getControl()).getText()))
			CmsSwtUtils.style(getControl(), FormStyle.propertyMessage);
		else
			CmsSwtUtils.style(getControl(), FormStyle.propertyText);
		super.stopEditing();
	}

	public String getPropertyName() {
		return propertyName;
	}
}