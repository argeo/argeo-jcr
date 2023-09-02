package org.argeo.cms.jcr.acr;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.QueryManager;
import javax.jcr.query.qom.DynamicOperand;
import javax.jcr.query.qom.QueryObjectModel;
import javax.jcr.query.qom.QueryObjectModelConstants;
import javax.jcr.query.qom.QueryObjectModelFactory;
import javax.jcr.query.qom.Selector;
import javax.jcr.query.qom.StaticOperand;
import javax.xml.namespace.QName;

import org.apache.jackrabbit.commons.query.sql2.QOMFormatter;
import org.argeo.api.acr.NamespaceUtils;
import org.argeo.api.acr.search.BasicSearch;
import org.argeo.api.acr.search.Constraint;
import org.argeo.api.acr.search.ContentFilter;
import org.argeo.api.acr.search.ContentFilter.Eq;
import org.argeo.api.acr.search.ContentFilter.IsContentClass;
import org.argeo.api.acr.search.ContentFilter.Not;
import org.argeo.api.cms.CmsLog;

/** Convert an ACR basic search to a JCR query. */
class BasicSearchToQom {
	private final static CmsLog log = CmsLog.getLog(BasicSearchToQom.class);

	private Session session;
	private QueryManager queryManager;
	private BasicSearch basicSearch;
	QueryObjectModelFactory factory;

	QName contentClass = null;

	String selectorName = "content";

	public BasicSearchToQom(Session session, BasicSearch basicSearch, String relPath) throws RepositoryException {
		this.session = session;
		this.queryManager = session.getWorkspace().getQueryManager();
		this.basicSearch = basicSearch;
		factory = queryManager.getQOMFactory();
	}

	public QueryObjectModel createQuery() throws RepositoryException {
		ContentFilter<?> where = basicSearch.getWhere();
		// scan for content classes
		// TODO deal with complex cases of multiple types

		javax.jcr.query.qom.Constraint qomConstraint = toQomConstraint(where);
		if (contentClass == null)
			throw new IllegalArgumentException("No content class specified");

		Selector source = factory.selector(NamespaceUtils.toPrefixedName(contentClass), selectorName);

		QueryObjectModel qom = factory.createQuery(source, qomConstraint, null, null);
		if (log.isDebugEnabled()) {
			String sql2 = QOMFormatter.format(qom);
			log.debug("JCR query:\n" + sql2 + "\n");
		}
		return qom;
	}

	private javax.jcr.query.qom.Constraint toQomConstraint(Constraint constraint) throws RepositoryException {
		javax.jcr.query.qom.Constraint qomConstraint;
		if (constraint instanceof ContentFilter<?> where) {
			List<Constraint> constraints = new ArrayList<>();
			for (Constraint c : where.getConstraints()) {
				if (c instanceof IsContentClass icc) {
					if (icc.getContentClasses().length > 1 || contentClass != null)
						throw new IllegalArgumentException("Multiple content class is not supported");
					contentClass = icc.getContentClasses()[0];
				} else {
					constraints.add(c);
				}
			}

			if (constraints.isEmpty()) {
				qomConstraint = null;
			} else if (constraints.size() == 1) {
				qomConstraint = toQomConstraint(constraints.get(0));
			} else {
				javax.jcr.query.qom.Constraint currQomConstraint = toQomConstraint(constraints.get(0));
				for (int i = 1; i < constraints.size(); i++) {
					Constraint c = constraints.get(i);
					javax.jcr.query.qom.Constraint subQomConstraint = toQomConstraint(c);
					if (subQomConstraint != null) // isContentClass leads to null QOM constraint
						if (where.isUnion()) {
							currQomConstraint = factory.or(currQomConstraint, subQomConstraint);
						} else {
							currQomConstraint = factory.and(currQomConstraint, subQomConstraint);
						}
				}
				qomConstraint = currQomConstraint;
			}

		} else if (constraint instanceof Eq comp) {
			DynamicOperand dynamicOperand = factory.propertyValue(selectorName,
					NamespaceUtils.toPrefixedName(comp.getProp()));
			// TODO better convert attribute value
			StaticOperand staticOperand = factory
					.literal(session.getValueFactory().createValue(comp.getValue().toString()));
			qomConstraint = factory.comparison(dynamicOperand, QueryObjectModelConstants.JCR_OPERATOR_EQUAL_TO,
					staticOperand);
		} else if (constraint instanceof Not not) {
			qomConstraint = factory.not(toQomConstraint(not.getNegated()));
		} else {
			throw new IllegalArgumentException("Constraint " + constraint.getClass() + " is not supported");
		}
		return qomConstraint;
	}
}
