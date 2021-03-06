package controllers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import play.Logger;
import play.data.validation.Required;
import play.mvc.Before;
import util.JavaExtensions;
import util.Util;

public class UploadAPI extends LoggedInController {

	@Before
	static void before(){
		Logger.info("[%s] %s %s", Security.connected(), request.method, request.path);
		String user = request.user;
		String password = request.password;
		if(Security.isConnected())
			return;
		if(!StringUtils.isEmpty(user)
				&& !StringUtils.isEmpty(password)){
			Logger.info("Try auth");
			if(Security.authenticate(user, password)){
				Logger.info("OK");
				session.put("username", user);
			}else{
				Logger.info("Failed");
				forbidden("Invalid user and/or password");
			}
		}else // required by most non-browser clients to force them to try basic auth
			unauthorized();
	}
	
	public static void dispatch(Long uploadId, String path) throws IOException{
		if("MKCOL".equals(request.method))
			mkdir(uploadId, path);
		else if("LOCK".equals(request.method))
			lock(uploadId, path);
		else if("UNLOCK".equals(request.method))
			unlock(uploadId, path);
		else if("PROPFIND".equals(request.method))
			propfind(uploadId, path);
		badRequest();
	}

	//
	// Stupid DAV shit

	private static void propfind(Long uploadId, String path) throws IOException {
		models.Upload upload = Upload.getUpload(uploadId);
		File uploadsDir = Util.getUploadDir(upload.id);
		File file = new File(uploadsDir, path);
		checkUploadPath(file, uploadsDir);
		
		if(!file.exists())
			notFound();

		StringBuilder ret = new StringBuilder("<?xml version='1.0' encoding='utf-8' ?>\n");
		ret.append("<multistatus xmlns='DAV:'>\n");
		if(file.isFile())
			propfindFile(file, upload, ret);
		else{
			// first one is the folder itself
			propfindFile(file, upload, ret);
			for(File child : file.listFiles())
				propfindFile(child, upload, ret);
		}
		ret.append("</multistatus>\n");
		response.status = 207; // MULTI STATUS
		renderXml(ret.toString());
	}
	
	private static void propfindFile(File file, models.Upload upload, StringBuilder xml) {
		String path = JavaExtensions.relativeTo(file, upload);
		xml.append("<response>\n");
		xml.append("  <href>/").append(path).append("</href>\n");
		xml.append("  <propstat>\n");
		xml.append("    <prop>\n");
		if(file.isDirectory())
			xml.append("      <resourcetype><collection/></resourcetype>\n");
		else
			xml.append("      <resourcetype/>\n");
		xml.append("    </prop>\n");
		xml.append("    <status>HTTP/1.1 200 OK</status>\n");
		xml.append("  </propstat>\n");
		xml.append("</response>\n");
	}

	private static void unlock(Long uploadId, String path) {
		noContent();
	}

	private static void lock(Long uploadId, String path) throws IOException {
		renderXml("<?xml version='1.0' encoding='utf-8' ?>\n"
+"<prop xmlns='DAV:'>\n"
+"  <lockdiscovery>\n"
+"    <activelock>\n"
+"      <locktype><write/></locktype>\n"
+"      <lockscope><exclusive/></lockscope>\n"
+"      <depth>infinity</depth>\n"
+"      <owner>\n"
+"        <href>FOO</href>\n"
+"      </owner>\n"
+"      <timeout>Second-604800</timeout>\n"
+"      <locktoken>\n"
+"        <href>urn:uuid:e71d4fae-5dec-22d6-fea5-00a0c91e6be4</href>\n"
+"      </locktoken>\n"
+"    </activelock>\n"
+"  </lockdiscovery>\n"
+"</prop>\n"
);
	}

	private static void mkdir(Long uploadId, String path) throws IOException{
		models.Upload upload = Upload.getUpload(uploadId);
		File uploadsDir = Util.getUploadDir(upload.id);
		File file = new File(uploadsDir, path);
		checkUploadPath(file, uploadsDir);
		if(file.isFile())
			error(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid path for MKCOL: "+path+" (is an existing file)");
			
		file.mkdirs();
		ok();
	}


	public static void addFile(Long uploadId, String path) throws IOException{
		models.Upload upload = Upload.getUpload(uploadId);
		if(request.body.available() > 0){
			File uploadsDir = Util.getUploadDir(upload.id);
			File file = new File(uploadsDir, path);
			checkUploadPath(file, uploadsDir);
			if(file.isDirectory())
				error(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid path for upload: "+path+" (is a directory)");
			
			file.getParentFile().mkdirs();
			FileUtils.copyInputStreamToFile(request.body, file);
			request.body.close();
			created();
		}
		error(HttpURLConnection.HTTP_BAD_REQUEST, "Empty file");
	}

	public static void viewFile(Long uploadId, String path) throws IOException{
		models.Upload upload = Upload.getUpload(uploadId);
		File uploadsDir = Util.getUploadDir(upload.id);
		File file = new File(uploadsDir, path);
		checkUploadPath(file, uploadsDir);
		
		if(!file.exists())
			notFound(path);
		
		if(file.isDirectory())
			render("Upload/viewFile.html", upload, file);
		else
			renderBinary(file);
	}

	private static void checkUploadPath(File file, File uploadsDir) throws IOException{
		String uploadsPath = uploadsDir.getCanonicalPath();
		String filePath = file.getCanonicalPath();
		if(!filePath.startsWith(uploadsPath))
			forbidden("Path is not in your uploads repository");
	}
	
	public static void deleteFile(Long uploadId, String path, boolean returnToBrowse) throws IOException{
		models.Upload upload = Upload.getUpload(uploadId);
		File uploadsDir = Util.getUploadDir(upload.id);
		File file = new File(uploadsDir, path);
		checkUploadPath(file, uploadsDir);
		if(uploadsDir.getCanonicalPath().equals(file.getCanonicalPath()))
			error(HttpURLConnection.HTTP_BAD_REQUEST, "Cannot delete root upload path: "+path);

		if(!file.exists())
			notFound(path);

		Logger.info("delete: %s exists: %s", path, file.exists());
		
		if(file.isDirectory()){
			FileUtils.deleteDirectory(file);
			flash("message", "Directory deleted");
		}else{
			file.delete();
			flash("message", "File deleted");
		}
		if(returnToBrowse){
			File parent = file.getParentFile();
			String parentPath = JavaExtensions.relativeTo(parent, upload);
			viewFile(upload.id, parentPath);
		}else
			Upload.view(uploadId);
	}

	public static void uploadRepo(Long uploadId, @Required File repo) throws ZipException, IOException{
		models.Upload upload = Upload.getUpload(uploadId);
		File uploadsDir = Util.getUploadDir(upload.id);

		if(validationFailed()){
			Upload.uploadRepoForm(uploadId);
		}
		
		ZipFile zip = new ZipFile(repo);
		try{
			// first check them all
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while(entries.hasMoreElements()){
				ZipEntry entry = entries.nextElement();
				// skip directories
				if(entry.isDirectory())
					continue;
				File file = new File(uploadsDir, entry.getName());
				checkUploadPath(file, uploadsDir);
				if(file.isDirectory())
					error(HttpURLConnection.HTTP_BAD_REQUEST, "Invalid path for upload: "+entry.getName()+" (is a directory)");
			}
			// then store
			entries = zip.entries();
			int files = 0;
			while(entries.hasMoreElements()){
				ZipEntry entry = entries.nextElement();
				// skip directories
				if(entry.isDirectory())
					continue;
				File file = new File(uploadsDir, entry.getName());

				files++;
				file.getParentFile().mkdirs();
				InputStream inputStream = zip.getInputStream(entry);
				try{
					FileUtils.copyInputStreamToFile(inputStream, file);
				}finally{
					inputStream.close();
				}
			}
			flash("message", "Uploaded "+files+" file"+(files>1 ?"s":""));
		}finally{
			zip.close();
		}
		
		Upload.view(uploadId);
	}
}