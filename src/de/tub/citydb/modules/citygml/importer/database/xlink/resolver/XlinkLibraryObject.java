/*
 * This file is part of the 3D City Database Importer/Exporter.
 * Copyright (c) 2007 - 2013
 * Institute for Geodesy and Geoinformation Science
 * Technische Universitaet Berlin, Germany
 * http://www.gis.tu-berlin.de/
 * 
 * The 3D City Database Importer/Exporter program is free software:
 * you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program. If not, see 
 * <http://www.gnu.org/licenses/>.
 * 
 * The development of the 3D City Database Importer/Exporter has 
 * been financially supported by the following cooperation partners:
 * 
 * Business Location Center, Berlin <http://www.businesslocationcenter.de/>
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * Berlin Senate of Business, Technology and Women <http://www.berlin.de/sen/wtf/>
 */
package de.tub.citydb.modules.citygml.importer.database.xlink.resolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import oracle.jdbc.OracleResultSet;
import oracle.sql.BLOB;
import de.tub.citydb.config.Config;
import de.tub.citydb.log.Logger;
import de.tub.citydb.modules.citygml.common.database.xlink.DBXlinkLibraryObject;

public class XlinkLibraryObject implements DBXlinkResolver {
	private final Logger LOG = Logger.getInstance();
	
	private final Connection externalFileConn;
	private final Config config;

	private PreparedStatement psPrepare;
	PreparedStatement psSelect;
	private String localPath;
	private boolean replacePathSeparator;

	public XlinkLibraryObject(Connection textureImageConn, Config config) throws SQLException {
		this.externalFileConn = textureImageConn;
		this.config = config;

		init();
	}

	private void init() throws SQLException {
		localPath = config.getInternal().getImportPath();
		replacePathSeparator = File.separatorChar == '/';

		psPrepare = externalFileConn.prepareStatement("update IMPLICIT_GEOMETRY set LIBRARY_OBJECT=empty_blob() where ID=?");
		psSelect = externalFileConn.prepareStatement("select LIBRARY_OBJECT from IMPLICIT_GEOMETRY where ID=? for update");
	}

	public boolean insert(DBXlinkLibraryObject xlink) throws SQLException {
		String objectFileName = xlink.getFileURI();
		boolean isRemote = true;
		URL objectURL = null;
		File objectFile = null;
		
		try {
			// first step: check whether we deal with a local or remote library object file
			try {
				objectURL = new URL(objectFileName);
				objectFileName = objectURL.toString();

			} catch (MalformedURLException malURL) {
				isRemote = false;
				
				if (replacePathSeparator)
					objectFileName = objectFileName.replace("\\", "/");
				
				objectFile = new File(objectFileName);
				if (!objectFile.isAbsolute()) {
					objectFileName = localPath + File.separator + objectFile.getPath();
					objectFile = new File(objectFileName);
				}
				
				// check minimum requirements for local library object file
				if (!objectFile.exists() || !objectFile.isFile() || !objectFile.canRead()) {
					LOG.error("Failed to read library object file '" + objectFileName + "'.");
					return false;
				} else if (objectFile.length() == 0) {
					LOG.error("Skipping 0 byte library object file '" + objectFileName + "'.");
					return false;
				}
			}
			
			// second step: prepare BLOB
			psPrepare.setLong(1, xlink.getId());
			psPrepare.executeUpdate();

			// third step: get prepared BLOB to fill it with contents
			psSelect.setLong(1, xlink.getId());
			OracleResultSet rs = (OracleResultSet)psSelect.executeQuery();
			if (!rs.next()) {
				LOG.error("Database error while importing library object: " + objectFileName);

				rs.close();
				externalFileConn.rollback();
				return false;
			}

			BLOB blob = rs.getBLOB(1);
			rs.close();

			// fourth step: try and upload library object data
			LOG.debug("Importing library object: " + objectFileName);

			InputStream in = null;

			if (isRemote) {
				in = objectURL.openStream();
			} else {
				in = new FileInputStream(objectFile);
			}

			if (in == null) {
				LOG.error("Database error while importing library object: " + objectFileName);

				externalFileConn.rollback();
				return false;
			}

			OutputStream out = blob.setBinaryStream(1L);

			int size = blob.getBufferSize();
			byte[] buffer = new byte[size];
			int length = -1;

			while ((length = in.read(buffer)) != -1)
				out.write(buffer, 0, length);
		
			in.close();
			out.close();
			blob.free();
			externalFileConn.commit();
			return true;
			
		} catch (FileNotFoundException fnfEx) {
			LOG.error("Failed to find library object file '" + objectFileName + "'.");

			externalFileConn.rollback();
			return false;
		} catch (IOException ioEx) {
			LOG.error("Failed to read library object file '" + objectFileName + "': " + ioEx.getMessage());

			externalFileConn.rollback();
			return false;
		} catch (SQLException sqlEx) {
			LOG.error("SQL error while importing library object '" + objectFileName + "': " + sqlEx.getMessage());

			externalFileConn.rollback();
			return false;
		} 
	}

	@Override
	public void executeBatch() throws SQLException {
		// we do not have any action here, since we are heavily committing and roll-backing
		// within the insert-method. that's also the reason why we need a separated connection instance.
	}

	@Override
	public void close() throws SQLException {
		psPrepare.close();
		psSelect.close();
	}

	@Override
	public DBXlinkResolverEnum getDBXlinkResolverType() {
		return DBXlinkResolverEnum.LIBRARY_OBJECT;
	}

}
