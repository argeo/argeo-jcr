package org.argeo.cms.jcr.swt;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;

import org.argeo.cms.ux.widgets.AbstractTabularPart;
import org.argeo.jcr.JcrException;

public class PagedJcrQueryTabularPart extends AbstractTabularPart<Query, Node> {
	private int pageSize = 100;
	private int cursor = 0;
	private int nextUpperBound = pageSize;
	private NodeIterator nit = null;

	@Override
	public int getItemCount() {
		return (nextUpperBound - pageSize) + (int) nit.getSize();
	}

	@Override
	public Node getData(int row) {
		// System.out.println("Row " + row);
		if (row == cursor) {
			cursor++;
			Node res = nit.nextNode();
			if (cursor == nextUpperBound) {
				getInput().setOffset(cursor);
				nextUpperBound = cursor + pageSize;
				try {
					nit = getInput().execute().getNodes();
				} catch (RepositoryException e) {
					throw new JcrException("Cannot refresh query", e);
				}
				notifyItemCountChange();
			}
			return res;
		} else {
			return null;
		}
	}

	@Override
	public void refresh() {
		getInput().setOffset(0);
		getInput().setLimit(pageSize);
		cursor = 0;
		nextUpperBound = pageSize;
		try {
			nit = getInput().execute().getNodes();
		} catch (RepositoryException e) {
			throw new JcrException("Cannot refresh query", e);
		}
		super.refresh();
	}

}
