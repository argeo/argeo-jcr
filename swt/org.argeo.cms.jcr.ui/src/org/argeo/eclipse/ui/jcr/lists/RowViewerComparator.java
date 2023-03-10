package org.argeo.eclipse.ui.jcr.lists;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.query.Row;

import org.argeo.eclipse.ui.EclipseUiException;
import org.eclipse.jface.viewers.Viewer;

/**
 * Base comparator to enable ordering on Table or Tree viewer that display Jcr
 * rows
 */
public class RowViewerComparator extends NodeViewerComparator {
	private static final long serialVersionUID = 7020939505172625113L;
	protected String selectorName;

	public RowViewerComparator() {
	}

	/**
	 * e1 and e2 must both be Jcr rows.
	 * 
	 * @param viewer
	 * @param e1
	 * @param e2
	 * @return the comparison
	 */
	@Override
	public int compare(Viewer viewer, Object e1, Object e2) {
		try {
			Node n1 = ((Row) e1).getNode(selectorName);
			Node n2 = ((Row) e2).getNode(selectorName);
			return super.compare(viewer, n1, n2);
		} catch (RepositoryException re) {
			throw new EclipseUiException("Unexpected error "
					+ "while comparing nodes", re);
		}
	}

	/**
	 * @param propertyType
	 *            Corresponding JCR type
	 * @param propertyName
	 *            name of the property to use.
	 */
	public void setColumn(int propertyType, String selectorName,
			String propertyName) {
		if (this.selectorName != null && getPropertyName() != null
				&& this.selectorName.equals(selectorName)
				&& this.getPropertyName().equals(propertyName)) {
			// Same column as last sort; toggle the direction
			setDirection(1 - getDirection());
		} else {
			// New column; do a descending sort
			setPropertyType(propertyType);
			setPropertyName(propertyName);
			this.selectorName = selectorName;
			setDirection(NodeViewerComparator.ASCENDING);
		}
	}
}
