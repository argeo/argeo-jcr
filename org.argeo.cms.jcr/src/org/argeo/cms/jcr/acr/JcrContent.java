package org.argeo.cms.jcr.acr;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.argeo.api.acr.Content;
import org.argeo.api.acr.CrAttributeType;
import org.argeo.api.acr.DName;
import org.argeo.api.acr.NamespaceUtils;
import org.argeo.api.acr.spi.ProvidedSession;
import org.argeo.api.cms.CmsConstants;
import org.argeo.cms.acr.AbstractContent;
import org.argeo.cms.acr.ContentUtils;
import org.argeo.jcr.Jcr;
import org.argeo.jcr.JcrException;
import org.argeo.jcr.JcrUtils;
import org.argeo.jcr.JcrxApi;

/** A JCR {@link Node} accessed as {@link Content}. */
public class JcrContent extends AbstractContent {
	private JcrContentProvider provider;

	private String jcrWorkspace;
	private String jcrPath;

	private final boolean isMountBase;

	/* OPTIMISATIONS */
	/**
	 * While we want to support thread-safe access, it is very likely that only
	 * thread and only one sesssion will be used (typically from a single-threaded
	 * UI). We therefore cache was long as the same thread is calling.
	 */
	private Thread lastRetrievingThread = null;
	private Node cachedNode = null;
	private boolean caching = true;

	protected JcrContent(ProvidedSession session, JcrContentProvider provider, String jcrWorkspace, String jcrPath) {
		super(session);
		this.provider = provider;
		this.jcrWorkspace = jcrWorkspace;
		this.jcrPath = jcrPath;

		this.isMountBase = ContentUtils.SLASH_STRING.equals(jcrPath);
	}

	/*
	 * READ
	 */

	@Override
	public QName getName() {
		String name = Jcr.getName(getJcrNode());
		if (name.equals("")) {// root
			String mountPath = provider.getMountPath();
			name = ContentUtils.getParentPath(mountPath)[1];
			// name = Jcr.getWorkspaceName(getJcrNode());
		}
		return NamespaceUtils.parsePrefixedName(provider, name);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <A> Optional<A> get(QName key, Class<A> clss) {
		Node node = getJcrNode();
		if (DName.creationdate.equals(key))
			key = JcrName.created.qName();
		else if (DName.getlastmodified.equals(key))
			key = JcrName.lastModified.qName();
		else if (DName.checkedOut.equals(key)) {
			try {
				if (!node.hasProperty(Property.JCR_IS_CHECKED_OUT))
					return Optional.empty();
				boolean isCheckedOut = node.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean();
				if (!isCheckedOut)
					return Optional.empty();
				// FIXME return URI
				return (Optional<A>) Optional.of(new Object());
			} catch (RepositoryException e) {
				throw new JcrException(e);
			}
		} else if (DName.checkedIn.equals(key)) {
			try {
				if (!node.hasProperty(Property.JCR_IS_CHECKED_OUT))
					return Optional.empty();
				boolean isCheckedOut = node.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean();
				if (isCheckedOut)
					return Optional.empty();
				// FIXME return URI
				return (Optional<A>) Optional.of(new Object());
			} catch (RepositoryException e) {
				throw new JcrException(e);
			}
		}

		Object value = get(node, key.toString());
		if (value instanceof List<?> lst)
			return Optional.of((A) lst);
		// TODO check other collections?
		return CrAttributeType.cast(clss, value);
	}

	@Override
	public Iterator<Content> iterator() {
		try {
			return new JcrContentIterator(getJcrNode().getNodes());
		} catch (RepositoryException e) {
			throw new JcrException("Cannot list children of " + getJcrNode(), e);
		}
	}

	@Override
	protected Iterable<QName> keys() {
		try {
			Node node = getJcrNode();
			Set<QName> keys = new HashSet<>();
			for (PropertyIterator propertyIterator = node.getProperties(); propertyIterator.hasNext();) {
				Property property = propertyIterator.nextProperty();
				QName name = NamespaceUtils.parsePrefixedName(provider, property.getName());

				// TODO convert standard names
				if (property.getName().equals(Property.JCR_CREATED))
					name = DName.creationdate.qName();
				if (property.getName().equals(Property.JCR_LAST_MODIFIED))
					name = DName.getlastmodified.qName();
				if (property.getName().equals(Property.JCR_IS_CHECKED_OUT)) {
					boolean isCheckedOut = node.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean();
					name = isCheckedOut ? DName.checkedOut.qName() : DName.checkedIn.qName();
				}

				// TODO skip technical properties
				keys.add(name);
			}
			return keys;
		} catch (RepositoryException e) {
			throw new JcrException("Cannot list properties of " + getJcrNode(), e);
		}
	}

	/** Cast to a standard Java object. */
	static Object get(Node node, String property) {
		try {
			if (!node.hasProperty(property))
				return null;
			Property p = node.getProperty(property);
			if (p.isMultiple()) {
				Value[] values = p.getValues();
				List<Object> lst = new ArrayList<>();
				for (Value value : values) {
					lst.add(convertSingleValue(value));
				}
				return lst;
			} else {
				Value value = node.getProperty(property).getValue();
				return convertSingleValue(value);
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot cast value from " + property + " of " + node, e);
		}
	}

	@Override
	public boolean isMultiple(QName key) {
		Node node = getJcrNode();
		String p = NamespaceUtils.toFullyQualified(key);
		try {
			if (node.hasProperty(p)) {
				Property property = node.getProperty(p);
				return property.isMultiple();
			} else {
				return false;
			}
		} catch (RepositoryException e) {
			throw new JcrException(
					"Cannot check multiplicityof property " + p + " of " + jcrPath + " in " + jcrWorkspace, e);
		}
	}

	@Override
	public String getPath() {
		try {
			// Note: it is important to to use the default way (recursing through parents),
			// since the session may not have access to parent nodes
			return Content.ROOT_PATH + jcrWorkspace + getJcrNode().getPath();
		} catch (RepositoryException e) {
			throw new JcrException("Cannot get depth of " + getJcrNode(), e);
		}
	}

	@Override
	public int getDepth() {
		try {
			return getJcrNode().getDepth() + 1;
		} catch (RepositoryException e) {
			throw new JcrException("Cannot get depth of " + getJcrNode(), e);
		}
	}

	@Override
	public Content getParent() {
		if (isMountBase) {
			String mountPath = provider.getMountPath();
			if (mountPath == null || mountPath.equals("/"))
				return null;
			String[] parent = ContentUtils.getParentPath(mountPath);
			return getSession().get(parent[0]);
		}
//		if (Jcr.isRoot(getJcrNode())) // root
//			return null;
		return new JcrContent(getSession(), provider, jcrWorkspace, Jcr.getParentPath(getJcrNode()));
	}

	@Override
	public int getSiblingIndex() {
		return Jcr.getIndex(getJcrNode());
	}

	@Override
	public String getText() {
		return JcrxApi.getXmlValue(getJcrNode());
	}

	/*
	 * MAP OPTIMISATIONS
	 */
	@Override
	public boolean containsKey(Object key) {
		return Jcr.hasProperty(getJcrNode(), key.toString());
	}

	/*
	 * WRITE
	 */

	protected Node openForEdit() {
		Node node = getProvider().openForEdit(getSession(), jcrWorkspace, jcrPath);
		getSession().notifyModification(this);
		return node;
	}

	@Override
	public Content add(QName name, QName... classes) {
		if (classes.length > 0) {
			QName primaryType = classes[0];
			Node node = openForEdit();
			Node child = Jcr.addNode(node, name.toString(), primaryType.toString());
			for (int i = 1; i < classes.length; i++) {
				try {
					child.addMixin(classes[i].toString());
				} catch (RepositoryException e) {
					throw new JcrException("Cannot add child to " + getJcrNode(), e);
				}
			}

		} else {
			Jcr.addNode(getJcrNode(), name.toString(), NodeType.NT_UNSTRUCTURED);
		}
		return null;
	}

	@Override
	public void remove() {
		Node node = openForEdit();
		Jcr.remove(node);
	}

	@Override
	protected void removeAttr(QName key) {
		Node node = openForEdit();
		Property property = Jcr.getProperty(node, key.toString());
		if (property != null) {
			try {
				property.remove();
			} catch (RepositoryException e) {
				throw new JcrException("Cannot remove property " + key + " from " + getJcrNode(), e);
			}
		}

	}

	@Override
	public Object put(QName key, Object value) {
		try {
			String property = NamespaceUtils.toFullyQualified(key);
			Node node = openForEdit();
			Object old = null;
			if (node.hasProperty(property)) {
				old = convertSingleValue(node.getProperty(property).getValue());
			}
			Value newValue = convertSingleObject(node.getSession().getValueFactory(), value);
			node.setProperty(property, newValue);
			// FIXME proper edition
			node.getSession().save();
			return old;
		} catch (RepositoryException e) {
			throw new JcrException("Cannot set property " + key + " on " + jcrPath + " in " + jcrWorkspace, e);
		}
	}

	@Override
	public void addContentClasses(QName... contentClass) throws IllegalArgumentException, JcrException {
		try {
			Node node = openForEdit();
			NodeTypeManager ntm = node.getSession().getWorkspace().getNodeTypeManager();
			List<NodeType> nodeTypes = new ArrayList<>();
			for (QName clss : contentClass) {
				NodeType nodeType = ntm.getNodeType(NamespaceUtils.toFullyQualified(clss));
				if (!nodeType.isMixin())
					throw new IllegalArgumentException(clss + " is not a mixin");
				nodeTypes.add(nodeType);
			}
			for (NodeType nodeType : nodeTypes) {
				node.addMixin(nodeType.getName());
			}
			// FIXME proper edition
			node.getSession().save();
		} catch (RepositoryException e) {
			throw new JcrException(
					"Cannot add content classes " + contentClass + " to " + jcrPath + " in " + jcrWorkspace, e);
		}
	}

	/*
	 * ACCESS
	 */
	protected boolean exists() {
		try {
			return getJcrSession().itemExists(jcrPath);
		} catch (RepositoryException e) {
			throw new JcrException("Cannot check whether " + jcrPath + " exists", e);
		}
	}

	@Override
	public boolean isParentAccessible() {
		String jcrParentPath = ContentUtils.getParentPath(jcrPath)[0];
		if ("".equals(jcrParentPath)) // JCR root node
			jcrParentPath = ContentUtils.SLASH_STRING;
		try {
			return getJcrSession().hasPermission(jcrParentPath, Session.ACTION_READ);
		} catch (RepositoryException e) {
			throw new JcrException("Cannot check whether parent " + jcrParentPath + " is accessible", e);
		}
	}

	/*
	 * ADAPTERS
	 */
	@SuppressWarnings("unchecked")
	public <A> A adapt(Class<A> clss) {
		if (Node.class.isAssignableFrom(clss)) {
			return (A) getJcrNode();
		} else if (Source.class.isAssignableFrom(clss)) {
//			try {
			PipedOutputStream out = new PipedOutputStream();
			PipedInputStream in;
			try {
				in = new PipedInputStream(out);
			} catch (IOException e) {
				throw new RuntimeException("Cannot export " + jcrPath + " in workspace " + jcrWorkspace, e);
			}

			ForkJoinPool.commonPool().execute(() -> {
//				try (PipedOutputStream out = new PipedOutputStream(in)) {
				try {
					getJcrSession().exportDocumentView(jcrPath, out, true, false);
					out.flush();
					out.close();
				} catch (IOException | RepositoryException e) {
					throw new RuntimeException("Cannot export " + jcrPath + " in workspace " + jcrWorkspace, e);
				}

			});
			return (A) new StreamSource(in);
//			} catch (IOException e) {
//				throw new RuntimeException("Cannot adapt " + JcrContent.this + " to " + clss, e);
//			}
		} else {
			return super.adapt(clss);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <C extends Closeable> C open(Class<C> clss) throws IOException, IllegalArgumentException {
		if (InputStream.class.isAssignableFrom(clss)) {
			Node node = getJcrNode();
			if (Jcr.isNodeType(node, NodeType.NT_FILE)) {
				try {
					return (C) JcrUtils.getFileAsStream(node);
				} catch (RepositoryException e) {
					throw new JcrException("Cannot open " + jcrPath + " in workspace " + jcrWorkspace, e);
				}
			}
		}
		return super.open(clss);
	}

	@Override
	public JcrContentProvider getProvider() {
		return provider;
	}

	@Override
	public String getSessionLocalId() {
		try {
			return getJcrNode().getIdentifier();
		} catch (RepositoryException e) {
			throw new JcrException("Cannot get identifier for " + getJcrNode(), e);
		}
	}

	/*
	 * TYPING
	 */

	static Object convertSingleValue(Value value) throws JcrException, IllegalArgumentException {
		try {
			switch (value.getType()) {
			case PropertyType.STRING:
				return value.getString();
			case PropertyType.DOUBLE:
				return (Double) value.getDouble();
			case PropertyType.LONG:
				return (Long) value.getLong();
			case PropertyType.BOOLEAN:
				return (Boolean) value.getBoolean();
			case PropertyType.DATE:
				Calendar calendar = value.getDate();
				return calendar.toInstant();
			case PropertyType.BINARY:
				throw new IllegalArgumentException("Binary is not supported as an attribute");
			default:
				return value.getString();
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot convert " + value + " to an object.", e);
		}
	}

	static Value convertSingleObject(ValueFactory factory, Object value) {
		if (value instanceof String string) {
			return factory.createValue(string);
		} else if (value instanceof Double dbl) {
			return factory.createValue(dbl);
		} else if (value instanceof Float flt) {
			return factory.createValue(flt);
		} else if (value instanceof Long lng) {
			return factory.createValue(lng);
		} else if (value instanceof Integer intg) {
			return factory.createValue(intg);
		} else if (value instanceof Boolean bool) {
			return factory.createValue(bool);
		} else if (value instanceof Instant instant) {
			GregorianCalendar calendar = new GregorianCalendar();
			calendar.setTime(Date.from(instant));
			return factory.createValue(calendar);
		} else {
			// TODO or use String by default?
			throw new IllegalArgumentException("Unsupported value " + value.getClass());
		}
	}

	@Override
	public Class<?> getType(QName key) {
		Node node = getJcrNode();
		String p = NamespaceUtils.toFullyQualified(key);
		try {
			if (node.hasProperty(p)) {
				Property property = node.getProperty(p);
				return switch (property.getType()) {
				case PropertyType.STRING:
				case PropertyType.NAME:
				case PropertyType.PATH:
				case PropertyType.DECIMAL:
					yield String.class;
				case PropertyType.LONG:
					yield Long.class;
				case PropertyType.DOUBLE:
					yield Double.class;
				case PropertyType.BOOLEAN:
					yield Boolean.class;
				case PropertyType.DATE:
					yield Instant.class;
				case PropertyType.WEAKREFERENCE:
				case PropertyType.REFERENCE:
					yield UUID.class;
				default:
					yield Object.class;
				};
			} else {
				// TODO does it make sense?
				return Object.class;
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot get type of property " + p + " of " + jcrPath + " in " + jcrWorkspace, e);
		}
	}

	@Override
	public List<QName> getContentClasses() {
		try {
			Node context = getJcrNode();

			List<QName> res = new ArrayList<>();
			// primary node type
			NodeType primaryType = context.getPrimaryNodeType();
			res.add(nodeTypeToQName(primaryType));

			Set<QName> secondaryTypes = new TreeSet<>(NamespaceUtils.QNAME_COMPARATOR);
			for (NodeType mixinType : context.getMixinNodeTypes()) {
				secondaryTypes.add(nodeTypeToQName(mixinType));
			}
			for (NodeType superType : primaryType.getDeclaredSupertypes()) {
				secondaryTypes.add(nodeTypeToQName(superType));
			}
			// mixins
			for (NodeType mixinType : context.getMixinNodeTypes()) {
				for (NodeType superType : mixinType.getDeclaredSupertypes()) {
					secondaryTypes.add(nodeTypeToQName(superType));
				}
			}
			res.addAll(secondaryTypes);
			return res;
		} catch (RepositoryException e) {
			throw new JcrException("Cannot list node types from " + getJcrNode(), e);
		}
	}

	private QName nodeTypeToQName(NodeType nodeType) {
		String name = nodeType.getName();
		return NamespaceUtils.parsePrefixedName(provider, name);
		// return QName.valueOf(name);
	}

	/*
	 * COMMON UTILITIES
	 */
	protected Session getJcrSession() {
		return provider.getJcrSession(getSession(), jcrWorkspace);
	}

	protected Node getJcrNode() {
		try {
			if (caching) {
				synchronized (this) {
					if (lastRetrievingThread != Thread.currentThread()) {
						cachedNode = getJcrSession().getNode(jcrPath);
						lastRetrievingThread = Thread.currentThread();
					}
					return cachedNode;
				}
			} else {
				return getJcrSession().getNode(jcrPath);
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot retrieve " + jcrPath + " from workspace " + jcrWorkspace, e);
		}
	}

	/*
	 * STATIC UTLITIES
	 */
	public static Content nodeToContent(Node node) {
		if (node == null)
			return null;
		try {
			ProvidedSession contentSession = (ProvidedSession) node.getSession()
					.getAttribute(ProvidedSession.class.getName());
			if (contentSession == null)
				throw new IllegalArgumentException(
						"Cannot adapt " + node + " to content, because it was not loaded from a content session");
			return contentSession.get(Content.ROOT_PATH + CmsConstants.SYS_WORKSPACE + node.getPath());
		} catch (RepositoryException e) {
			throw new JcrException("Cannot adapt " + node + " to a content", e);
		}
	}

	/*
	 * CONTENT ITERATOR
	 */

	class JcrContentIterator implements Iterator<Content> {
		private final NodeIterator nodeIterator;
		// we keep track in order to be able to delete it
		private JcrContent current = null;

		protected JcrContentIterator(NodeIterator nodeIterator) {
			this.nodeIterator = nodeIterator;
		}

		@Override
		public boolean hasNext() {
			return nodeIterator.hasNext();
		}

		@Override
		public Content next() {
			current = new JcrContent(getSession(), provider, jcrWorkspace, Jcr.getPath(nodeIterator.nextNode()));
			return current;
		}

		@Override
		public void remove() {
			if (current != null) {
				Jcr.remove(current.getJcrNode());
			}
		}

	}

}
