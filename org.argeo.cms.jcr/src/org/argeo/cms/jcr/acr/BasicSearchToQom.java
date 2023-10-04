package org.argeo.cms.jcr.acr;

import static javax.jcr.query.qom.QueryObjectModelConstants.JCR_OPERATOR_EQUAL_TO;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Property;
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
import org.argeo.api.acr.DName;
import org.argeo.api.acr.NamespaceUtils;
import org.argeo.api.acr.search.BasicSearch;
import org.argeo.api.acr.search.Constraint;
import org.argeo.api.acr.search.ContentFilter;
import org.argeo.api.acr.search.Eq;
import org.argeo.api.acr.search.Gt;
import org.argeo.api.acr.search.Gte;
import org.argeo.api.acr.search.IsContentClass;
import org.argeo.api.acr.search.IsDefined;
import org.argeo.api.acr.search.Like;
import org.argeo.api.acr.search.Lt;
import org.argeo.api.acr.search.Lte;
import org.argeo.api.acr.search.Not;
import org.argeo.api.acr.search.PropertyValueContraint;
import org.argeo.api.cms.CmsLog;

/** Convert an ACR basic search to a JCR query. */
class BasicSearchToQom {
	private final static CmsLog log = CmsLog.getLog(BasicSearchToQom.class);

	private Session session;
	private QueryManager queryManager;
	private BasicSearch basicSearch;
	private QueryObjectModelFactory factory;

	private String relPath;

	private QName contentClass = null;

	private String selectorName = "content";

	public BasicSearchToQom(Session session, BasicSearch basicSearch, String relPath) throws RepositoryException {
		this.session = session;
		this.queryManager = session.getWorkspace().getQueryManager();
		this.basicSearch = basicSearch;
		this.relPath = relPath;
		factory = queryManager.getQOMFactory();
	}

	public QueryObjectModel createQuery() throws RepositoryException {
		ContentFilter<?> where = basicSearch.getWhere();
		// scan for content classes
		// TODO deal with complex cases of multiple types

		javax.jcr.query.qom.Constraint qomConstraint = toQomConstraint(where);
		if (contentClass == null)
			throw new IllegalArgumentException("No content class specified");

		if (relPath != null) {
			qomConstraint = factory.and(qomConstraint, factory.descendantNode(selectorName, "/" + relPath));
		}

		Selector source = factory.selector(NamespaceUtils.toPrefixedName(contentClass), selectorName);

		QueryObjectModel qom = factory.createQuery(source, qomConstraint, null, null);
		if (log.isDebugEnabled()) {
			String sql2 = QOMFormatter.format(qom);
			log.debug("JCR query:\n" + sql2 + "\n");
		}
		return qom;
	}

	private javax.jcr.query.qom.Constraint toQomConstraint(Constraint constraint) throws RepositoryException {
//		javax.jcr.query.qom.Constraint qomConstraint;
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
				return null;
			} else if (constraints.size() == 1) {
				return toQomConstraint(constraints.get(0));
			} else {
				javax.jcr.query.qom.Constraint currQomConstraint = toQomConstraint(constraints.get(0));
				// QOM constraint may be null because only content classes where specified
				while (currQomConstraint == null) {
					constraints.remove(0);
					if (constraints.isEmpty())
						return null;
					currQomConstraint = toQomConstraint(constraints.get(0));
				}
				assert currQomConstraint != null : "currQomConstraint is null : " + constraints.get(0);
				for (int i = 1; i < constraints.size(); i++) {
					Constraint c = constraints.get(i);
					javax.jcr.query.qom.Constraint subQomConstraint = toQomConstraint(c);
					if (subQomConstraint != null) { // isContentClass leads to null QOM constraint
						assert subQomConstraint != null : "subQomConstraint";
						if (where.isUnion()) {
							currQomConstraint = factory.or(currQomConstraint, subQomConstraint);
						} else {
							currQomConstraint = factory.and(currQomConstraint, subQomConstraint);
						}
					}
				}
				return currQomConstraint;
			}

		} else if (constraint instanceof PropertyValueContraint comp) {
			QName prop = comp.getProp();
			if (DName.creationdate.equals(prop))
				prop = JcrName.created.qName();
			else if (DName.getlastmodified.equals(prop))
				prop = JcrName.lastModified.qName();

			DynamicOperand dynamicOperand = factory.propertyValue(selectorName, NamespaceUtils.toPrefixedName(prop));
			// TODO better convert attribute value
			StaticOperand staticOperand = factory
					.literal(JcrContent.convertSingleObject(session.getValueFactory(), comp.getValue()));
			javax.jcr.query.qom.Constraint res;
			if (comp instanceof Eq)
				res = factory.comparison(dynamicOperand, QueryObjectModelConstants.JCR_OPERATOR_EQUAL_TO,
						staticOperand);
			else if (comp instanceof Lt)
				res = factory.comparison(dynamicOperand, QueryObjectModelConstants.JCR_OPERATOR_LESS_THAN,
						staticOperand);
			else if (comp instanceof Lte)
				res = factory.comparison(dynamicOperand, QueryObjectModelConstants.JCR_OPERATOR_LESS_THAN_OR_EQUAL_TO,
						staticOperand);
			else if (comp instanceof Gt)
				res = factory.comparison(dynamicOperand, QueryObjectModelConstants.JCR_OPERATOR_GREATER_THAN,
						staticOperand);
			else if (comp instanceof Gte)
				res = factory.comparison(dynamicOperand,
						QueryObjectModelConstants.JCR_OPERATOR_GREATER_THAN_OR_EQUAL_TO, staticOperand);
			else if (comp instanceof Like)
				res = factory.comparison(dynamicOperand, QueryObjectModelConstants.JCR_OPERATOR_LIKE, staticOperand);
			else
				throw new UnsupportedOperationException("Constraint of type " + comp.getClass() + " is not supported");
			return res;
		} else if (constraint instanceof Not not) {
			return factory.not(toQomConstraint(not.getNegated()));
		} else if (constraint instanceof IsDefined comp) {
			QName prop = comp.getProp();
			if (DName.checkedIn.equals(prop) || DName.checkedOut.equals(prop)) {
				DynamicOperand dynamicOperand = factory.propertyValue(selectorName, Property.JCR_IS_CHECKED_OUT);
				StaticOperand staticOperand = factory
						.literal(session.getValueFactory().createValue(DName.checkedOut.equals(prop)));
				return factory.comparison(dynamicOperand, JCR_OPERATOR_EQUAL_TO, staticOperand);
			} else {
				return factory.propertyExistence(selectorName, NamespaceUtils.toPrefixedName(prop));
			}
		} else {
			throw new IllegalArgumentException("Constraint " + constraint.getClass() + " is not supported");
		}
//		return qomConstraint;
	}
}
