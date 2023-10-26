package org.argeo.cms.jcr.acr;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

import javax.jcr.Binary;
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
import org.argeo.cms.util.AsyncPipedOutputStream;
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
	 * While we want to support thread-safe access, it is very likely that only one
	 * thread and only one session will be used (typically from a single-threaded
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
		try {
			Node node = getJcrNode();
			if (DName.creationdate.equals(key))
				key = JcrName.created.qName();
			else if (DName.getlastmodified.equals(key))
				key = JcrName.lastModified.qName();
			else if (DName.getcontenttype.equals(key)) {
				String contentType = null;
				if (node.isNodeType(NodeType.NT_FILE)) {
					Node content = node.getNode(Node.JCR_CONTENT);
					if (content.isNodeType(NodeType.MIX_MIMETYPE)) {
						contentType = content.getProperty(Property.JCR_MIMETYPE).getString();
						if (content.hasProperty(Property.JCR_ENCODING))
							contentType = contentType + ";encoding="
									+ content.getProperty(Property.JCR_ENCODING).getString();
					}
				}
				if (contentType == null)
					contentType = "application/octet-stream";
				return CrAttributeType.cast(clss, contentType);
			} else if (DName.checkedOut.equals(key)) {
				if (!node.hasProperty(Property.JCR_IS_CHECKED_OUT))
					return Optional.empty();
				boolean isCheckedOut = node.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean();
				if (!isCheckedOut)
					return Optional.empty();
				// FIXME return URI
				return (Optional<A>) Optional.of(new Object());
			} else if (DName.checkedIn.equals(key)) {
				if (!node.hasProperty(Property.JCR_IS_CHECKED_OUT))
					return Optional.empty();
				boolean isCheckedOut = node.getProperty(Property.JCR_IS_CHECKED_OUT).getBoolean();
				if (isCheckedOut)
					return Optional.empty();
				// FIXME return URI
				return (Optional<A>) Optional.of(new Object());
			}

			Object value = get(node, key.toString());
			if (value instanceof List<?> lst)
				return Optional.of((A) lst);
			// TODO check other collections?
			return CrAttributeType.cast(clss, value);
		} catch (RepositoryException e) {
			throw new JcrException(e);
		}
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
				else if (property.getName().equals(Property.JCR_LAST_MODIFIED))
					name = DName.getlastmodified.qName();
				else if (property.getName().equals(Property.JCR_MIMETYPE))
					name = DName.getcontenttype.qName();
				else if (property.getName().equals(Property.JCR_IS_CHECKED_OUT)) {
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
					"Cannot check multiplicity of property " + p + " of " + jcrPath + " in " + jcrWorkspace, e);
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
		try {
			Node child;
			if (classes.length > 0) {
				QName primaryType = classes[0];
				Node node = openForEdit();
				child = Jcr.addNode(node, name.toString(), primaryType.toString());

				for (int i = 1; i < classes.length; i++)
					child.addMixin(classes[i].toString());

				if (NtType.file.qName().equals(primaryType)) {
					// TODO optimise when we have a proper save mechanism
					child.addNode(Node.JCR_CONTENT, NodeType.NT_UNSTRUCTURED);
//					Binary binary;
//					try (InputStream in = new ByteArrayInputStream(new byte[0])) {
//						binary = content.getSession().getValueFactory().createBinary(in);
//						content.setProperty(Property.JCR_DATA, binary);
//					} catch (IOException e) {
//						throw new UncheckedIOException(e);
//					}
					child.getSession().save();
				}
			} else {
				child = Jcr.addNode(getJcrNode(), name.toString(), NodeType.NT_UNSTRUCTURED);
			}
			return new JcrContent(getSession(), provider, jcrWorkspace, child.getPath());
		} catch (RepositoryException e) {
			throw new JcrException("Cannot add child to " + jcrPath + " in " + jcrWorkspace, e);
		}
	}

	@Override
	public Content add(QName name, Map<QName, Object> attrs, QName... classes) {
		if (attrs.containsKey(DName.getcontenttype.qName())) {
			List<QName> lst = new ArrayList<>(Arrays.asList(classes));
			lst.add(0, NtType.file.qName());
			classes = lst.toArray(new QName[lst.size()]);
		}
		if (attrs.containsKey(DName.collection.qName())) {
			List<QName> lst = Arrays.asList(classes);
			lst.add(0, NtType.folder.qName());
			classes = lst.toArray(new QName[lst.size()]);
		}
		Content child = add(name, classes);
		child.putAll(attrs);
		return child;
	}

	@Override
	public void remove() {
		Node node = openForEdit();
		Jcr.remove(node);
		saveEditedNode(node);
	}

	private void saveEditedNode(Node node) {
		try {
			node.getSession().save();
			getJcrSession().refresh(true);
		} catch (RepositoryException e) {
			throw new JcrException("Cannot persist " + jcrPath + " in " + jcrWorkspace, e);
		}
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
		saveEditedNode(node);
	}

	@Override
	public Object put(QName key, Object value) {
		Objects.requireNonNull(value, "Value cannot be null");
		Node node = openForEdit();
		try {
			if (DName.checkedIn.equals(key) || DName.checkedOut.equals(key))
				throw new IllegalArgumentException(
						key + " cannot be set, use the openForEdit/freeze methods of the related content provider.");

			Object old = null;
			String property;
			if (DName.creationdate.equals(key))
				property = Property.JCR_CREATED;
			else if (DName.getlastmodified.equals(key))
				property = Property.JCR_LAST_MODIFIED;
			else if (DName.getcontenttype.equals(key)) {
				if (!node.isNodeType(NodeType.NT_FILE))
					throw new IllegalStateException(DName.getcontenttype + " can only be set on a file");
				Node content = node.getNode(Node.JCR_CONTENT);
				old = Jcr.get(content, Property.JCR_MIMETYPE);
				if (old != null && Jcr.hasProperty(content, Property.JCR_ENCODING))
					old = old + ";encoding=" + Jcr.get(content, Property.JCR_ENCODING);
				String[] str = value.toString().split(";");
				String mimeType = str[0].trim();
				String encoding = null;
				if (str.length > 1) {
					value = str[0].trim();
					String[] eq = str[1].split("=");
					assert eq.length == 2;
					if ("encoding".equals(eq[0].trim()))
						encoding = eq[1];
				}
				content.setProperty(Property.JCR_MIMETYPE, mimeType);
				if (encoding != null)
					content.setProperty(Property.JCR_ENCODING, encoding);
				property = null;
			} else
				property = NamespaceUtils.toFullyQualified(key);

			if (property != null) {
				if (node.hasProperty(property)) {
					old = convertSingleValue(node.getProperty(property).getValue());
				}
				Value newValue = convertSingleObject(node.getSession().getValueFactory(), value);
				node.setProperty(property, newValue);
			}
			// FIXME proper edition
			saveEditedNode(node);
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
			saveEditedNode(node);
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
		try {
			if (InputStream.class.isAssignableFrom(clss)) {
				Node node = getJcrNode();
//				System.out.println(node.getSession());
				if (Jcr.isNodeType(node, NodeType.NT_FILE)) {
					return (C) JcrUtils.getFileAsStream(node);
				}
			} else if (OutputStream.class.isAssignableFrom(clss)) {
				Node node = openForEdit();
//				System.out.println(node.getSession());
				if (Jcr.isNodeType(node, NodeType.NT_FILE)) {
					Node content = node.getNode(Node.JCR_CONTENT);
					AsyncPipedOutputStream out = new AsyncPipedOutputStream();

					ValueFactory valueFactory = getJcrSession().getValueFactory();
					out.asyncRead((in) -> {
						try {
							Binary binary = valueFactory.createBinary(in);
							content.setProperty(Property.JCR_DATA, binary);
							saveEditedNode(node);
						} catch (RepositoryException e) {
							throw new JcrException(
									"Cannot create binary in " + jcrPath + " in workspace " + jcrWorkspace, e);
						}
					});
					return (C) out;
				}
			}
		} catch (RepositoryException e) {
			throw new JcrException("Cannot open " + jcrPath + " in workspace " + jcrWorkspace, e);
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
			if (primaryType.getName().equals(NodeType.NT_FOLDER))
				res.add(DName.collection.qName());

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
		Session s = provider.getJcrSession(getSession(), jcrWorkspace);
//		if (getSession().isEditing())
//			try {
//				s.refresh(false);
//			} catch (RepositoryException e) {
//				throw new JcrException("Cannot refresh session", e);
//			}
		return s;
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
