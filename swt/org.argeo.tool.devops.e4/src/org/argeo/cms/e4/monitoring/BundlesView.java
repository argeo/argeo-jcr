//package org.argeo.eclipse.ui.workbench.osgi;
//public class BundlesView {}

package org.argeo.cms.e4.monitoring;

import org.argeo.eclipse.ui.ColumnViewerComparator;
import org.eclipse.e4.ui.di.Focus;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import jakarta.annotation.PostConstruct;

/**
 * Overview of the bundles as a table. Equivalent to Equinox 'ss' console
 * command.
 */
public class BundlesView {
	private final static BundleContext bc = FrameworkUtil.getBundle(BundlesView.class).getBundleContext();
	private TableViewer viewer;

	@PostConstruct
	public void createPartControl(Composite parent) {
		viewer = new TableViewer(parent);
		viewer.setContentProvider(new BundleContentProvider());
		viewer.getTable().setHeaderVisible(true);

		//EclipseUiSpecificUtils.enableToolTipSupport(viewer);

		// ID
		TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setWidth(30);
		column.getColumn().setText("ID");
		column.getColumn().setAlignment(SWT.RIGHT);
		column.setLabelProvider(new ColumnLabelProvider() {
			private static final long serialVersionUID = -3122136344359358605L;

			public String getText(Object element) {
				return Long.toString(((Bundle) element).getBundleId());
			}
		});
		new ColumnViewerComparator(column);

		// State
		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setWidth(18);
		column.getColumn().setText("State");
		column.setLabelProvider(new StateLabelProvider());
		new ColumnViewerComparator(column);

		// Symbolic name
		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setWidth(250);
		column.getColumn().setText("Symbolic Name");
		column.setLabelProvider(new ColumnLabelProvider() {
			private static final long serialVersionUID = -4280840684440451080L;

			public String getText(Object element) {
				return ((Bundle) element).getSymbolicName();
			}
		});
		new ColumnViewerComparator(column);

		// Version
		column = new TableViewerColumn(viewer, SWT.NONE);
		column.getColumn().setWidth(250);
		column.getColumn().setText("Version");
		column.setLabelProvider(new ColumnLabelProvider() {
			private static final long serialVersionUID = 6871926308708629989L;

			public String getText(Object element) {
				Bundle bundle = (org.osgi.framework.Bundle) element;
				return bundle.getVersion().toString();
			}
		});
		new ColumnViewerComparator(column);

		viewer.setInput(bc);

	}

	@Focus
	public void setFocus() {
		if (viewer != null)
			viewer.getControl().setFocus();
	}

	/** Content provider managing the array of bundles */
	private static class BundleContentProvider implements IStructuredContentProvider {
		private static final long serialVersionUID = -8533792785725875977L;

		public Object[] getElements(Object inputElement) {
			if (inputElement instanceof BundleContext) {
				BundleContext bc = (BundleContext) inputElement;
				return bc.getBundles();
			}
			return null;
		}

		public void dispose() {
		}

		public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		}
	}
}
