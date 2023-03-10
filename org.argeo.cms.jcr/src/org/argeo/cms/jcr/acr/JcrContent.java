package org.argeo.cms.jcr.acr;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.argeo.api.acr.Content;
import org.argeo.api.acr.NamespaceUtils;
import org.argeo.api.acr.spi.ContentProvider;
import org.argeo.api.acr.spi.ProvidedSession;
import org.argeo.api.cms.CmsConstants;
import org.argeo.cms.acr.AbstractContent;
import org.argeo.cms.acr.ContentUtils;
import org.argeo.jcr.Jcr;
import org.argeo.jcr.JcrException;
import org.argeo.jcr.JcrUtils;

/** A JCR {@link Node} accessed as {@link Content}. */
public class JcrContent extends AbstractContent {
//	private Node jcrNode;

	private JcrContentProvider provider;

	private String jcrWorkspace;
	private String jcrPath;

	protected JcrContent(ProvidedSession session, JcrContentProvider provider, String jcrWorkspace, String jcrPath) {
		super(session);
		this.provider = provider;
		this.jcrWorkspace = jcrWorkspace;
		this.jcrPath = jcrPath;
	}

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
		if (isDefaultAttrTypeRequested(clss)) {
			return Optional.of((A) get(getJcrNode(), key.toString()));
		}
		return Optional.of((A) Jcr.get(getJcrNode(), key.toString()));
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
			Set<QName> keys = new HashSet<>();
			for (PropertyIterator propertyIterator = getJcrNode().getProperties(); propertyIterator.hasNext();) {
				Property property = propertyIterator.nextProperty();
				// TODO convert standard names
				// TODO skip technical properties
				QName name = NamespaceUtils.parsePrefixedName(provider, property.getName());
				keys.add(name);
			}
			return keys;
		} catch (RepositoryException e) {
			throw new JcrException("Cannot list properties of " + getJcrNode(), e);
		}
	}

	public Node getJcrNode() {
		try {
			// TODO caching?
			return provider.getJcrSession(getSession(), jcrWorkspace).getNode(jcrPath);
		} catch (RepositoryException e) {
			throw new JcrException("Cannot retrieve " + jcrPath + " from workspace " + jcrWorkspace, e);
		}
	}

	/** Cast to a standard Java object. */
	static Object get(Node node, String property) {
		try {
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
			throw new JcrException("Cannot cast value from " + property + " of node " + node, e);
		}
	}

	static Object convertSingleValue(Value value) throws RepositoryException {
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
	}

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

	@Override
	public String getPath() {
		try {
			// Note: it is important to to use the default way (recursing through parents),
			// since the session may not have access to parent nodes
			return ContentUtils.ROOT_SLASH + jcrWorkspace + getJcrNode().getPath();
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
		if (Jcr.isRoot(getJcrNode())) // root
			return null;
		return new JcrContent(getSession(), provider, jcrWorkspace, Jcr.getParentPath(getJcrNode()));
	}

	@Override
	public Content add(QName name, QName... classes) {
		if (classes.length > 0) {
			QName primaryType = classes[0];
			Node child = Jcr.addNode(getJcrNode(), name.toString(), primaryType.toString());
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
		Jcr.remove(getJcrNode());
	}

	@Override
	protected void removeAttr(QName key) {
		Property property = Jcr.getProperty(getJcrNode(), key.toString());
		if (property != null) {
			try {
				property.remove();
			} catch (RepositoryException e) {
				throw new JcrException("Cannot remove property " + key + " from " + getJcrNode(), e);
			}
		}

	}

	boolean exists() {
		try {
			return provider.getJcrSession(getSession(), jcrWorkspace).itemExists(jcrPath);
		} catch (RepositoryException e) {
			throw new JcrException("Cannot check whether " + jcrPath + " exists", e);
		}
	}

	/*
	 * ADAPTERS
	 */
	@SuppressWarnings("unchecked")
	public <A> A adapt(Class<A> clss) {
		if (Source.class.isAssignableFrom(clss)) {
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
					provider.getJcrSession(getSession(), jcrWorkspace).exportDocumentView(jcrPath, out, true, false);
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
		} else

			return super.adapt(clss);
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
	public ContentProvider getProvider() {
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
	@Override
	public List<QName> getContentClasses() {
		try {
//			Node node = getJcrNode();
//			List<QName> res = new ArrayList<>();
//			res.add(nodeTypeToQName(node.getPrimaryNodeType()));
//			for (NodeType mixin : node.getMixinNodeTypes()) {
//				res.add(nodeTypeToQName(mixin));
//			}
//			return res;
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
//		// entity type
//		if (context.isNodeType(EntityType.entity.get())) {
//			if (context.hasProperty(EntityNames.ENTITY_TYPE)) {
//				String entityTypeName = context.getProperty(EntityNames.ENTITY_TYPE).getString();
//				if (byType.containsKey(entityTypeName)) {
//					types.add(entityTypeName);
//				}
//			}
//		}
			res.addAll(secondaryTypes);
			return res;
		} catch (RepositoryException e) {
			throw new JcrException("Cannot list node types from " + getJcrNode(), e);
		}
	}

	private QName nodeTypeToQName(NodeType nodeType) {
		String name = nodeType.getName();
		return QName.valueOf(name);
	}

	@Override
	public int getSiblingIndex() {
		return Jcr.getIndex(getJcrNode());
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
			return contentSession.get(ContentUtils.SLASH + CmsConstants.SYS_WORKSPACE + node.getPath());
		} catch (RepositoryException e) {
			throw new JcrException("Cannot adapt " + node + " to a content", e);
		}
	}

}
