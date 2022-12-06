package org.argeo.cms.jcr.swt;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;

import org.argeo.cms.ux.widgets.AbstractTabularPart;
import org.argeo.jcr.JcrException;

public class JcrQueryTabularPart extends AbstractTabularPart<Query, Node> {
	private int cursor = 0;
	private NodeIterator nit = null;

	@Override
	public int getItemCount() {
		return (int) nit.getSize();
	}

	@Override
	public Node getData(int row) {
		// System.out.println("Row " + row);
		Node res;
		if (row == cursor) {
			res = nit.nextNode();
			cursor++;
		} else if (row > cursor) {
			nit.skip(row - cursor);
			cursor = row;
			res = nit.nextNode();
			cursor++;
		} else if (row < cursor) {
			try {
				nit = getInput().execute().getNodes();
			} catch (RepositoryException e) {
				throw new JcrException("Cannot refresh query", e);
			}
			notifyItemCountChange();
			nit.skip(row);
			cursor = row;
			res = nit.nextNode();
			cursor++;
		} else {
			throw new IllegalStateException("Cursor is " + cursor + " and row is " + row);
		}
		return res;
	}

	@Override
	public void refresh() {
		cursor = 0;
		try {
			nit = getInput().execute().getNodes();
		} catch (RepositoryException e) {
			throw new JcrException("Cannot refresh query", e);
		}
		super.refresh();
	}

	public long getQuerySize() {
		return nit.getSize();
	}
}
