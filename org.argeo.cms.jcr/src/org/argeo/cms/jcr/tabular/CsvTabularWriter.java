package org.argeo.cms.jcr.tabular;

import java.io.OutputStream;

import org.argeo.api.acr.tabular.TabularWriter;
import org.argeo.cms.util.CsvWriter;

/** Write tabular content in a stream as CSV. Wraps a {@link CsvWriter}. */
public class CsvTabularWriter implements TabularWriter {
	private CsvWriter csvWriter;

	public CsvTabularWriter(OutputStream out) {
		this.csvWriter = new CsvWriter(out);
	}

	public void appendRow(Object[] row) {
		csvWriter.writeLine(row);
	}

	public void close() {
	}

}
