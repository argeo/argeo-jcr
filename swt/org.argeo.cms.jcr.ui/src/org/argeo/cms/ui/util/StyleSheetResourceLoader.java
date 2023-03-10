package org.argeo.cms.ui.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.argeo.cms.swt.CmsException;
import org.eclipse.rap.rwt.service.ResourceLoader;
import org.osgi.framework.Bundle;

/** {@link ResourceLoader} caching stylesheets. */
public class StyleSheetResourceLoader implements ResourceLoader {
	private Bundle themeBundle;
	private Map<String, StyleSheet> stylesheets = new LinkedHashMap<String, StyleSheet>();

	public StyleSheetResourceLoader(Bundle themeBundle) {
		this.themeBundle = themeBundle;
	}

	@Override
	public InputStream getResourceAsStream(String resourceName) throws IOException {
		if (!stylesheets.containsKey(resourceName)) {
			// TODO deal with other bundles
			// Bundle bundle = bundleContext.getBundle();
			// String location =
			// bundle.getLocation().substring("initial@reference:".length());
			// if (location.startsWith("file:")) {
			// Path path = null;
			// try {
			// path = Paths.get(new URI(location));
			// } catch (URISyntaxException e) {
			// e.printStackTrace();
			// }
			// if (path != null) {
			// Path resourcePath = path.resolve(resourceName);
			// if (Files.exists(resourcePath))
			// return Files.newInputStream(resourcePath);
			// }
			// }

			URL res = themeBundle.getEntry(resourceName);
			if (res == null)
				throw new CmsException(
						"Entry " + resourceName + " not found in bundle " + themeBundle.getSymbolicName());
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			IOUtils.copy(res.openStream(), out);
			stylesheets.put(resourceName, new StyleSheet(out.toByteArray()));
		}
		return new ByteArrayInputStream(stylesheets.get(resourceName).getData());
		// return res.openStream();
	}

	private class StyleSheet {
		private byte[] data;

		public StyleSheet(byte[] data) {
			super();
			this.data = data;
		}

		public byte[] getData() {
			return data;
		}

	}
}
