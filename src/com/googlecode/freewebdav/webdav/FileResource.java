package com.googlecode.freewebdav.webdav;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.logging.Logger;

import com.bradmcevoy.http.Auth;
import com.bradmcevoy.http.CollectionResource;
import com.bradmcevoy.http.FileItem;
import com.bradmcevoy.http.LockInfo;
import com.bradmcevoy.http.LockResult;
import com.bradmcevoy.http.LockTimeout;
import com.bradmcevoy.http.LockToken;
import com.bradmcevoy.http.LockableResource;
import com.bradmcevoy.http.PropPatchableResource;
import com.bradmcevoy.http.Range;
import com.bradmcevoy.http.ReplaceableResource;
import com.bradmcevoy.http.exceptions.BadRequestException;
import com.bradmcevoy.http.exceptions.ConflictException;
import com.bradmcevoy.http.exceptions.LockedException;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.http.exceptions.PreConditionFailedException;
import com.bradmcevoy.http.webdav.PropPatchHandler.Fields;
import com.bradmcevoy.io.StreamUtils;
import com.googlecode.freewebdav.entities.WebdavFile;
import com.googlecode.freewebdav.entities.WebdavFileData;

public class FileResource extends AuthenticatedResource implements LockableResource, ReplaceableResource, PropPatchableResource, com.bradmcevoy.http.FileResource {
	private static final Logger log = Logger.getLogger(FileResource.class.getName());
	public static Long MAX_AGE = 60L * 60L; // 1hr
	MemcacheLockManager lockMan = new MemcacheLockManager();
	
	protected FileResource(WebdavFile wf) { super(wf); }

	protected WebdavFile getFile() {
		return (WebdavFile) item;
	}

	protected WebdavFileData getData() {
		return ofy.get(getFile().getData());
	}
	
	@Override
	public Long getContentLength() {
		return getFile().getBytes();
	}
	@Override
	public String getContentType(String accepts) {
		return FolderResource.fixCT(getFile().getContentType());
	}
	@Override
	public Long getMaxAgeSeconds(Auth auth) {
		return MAX_AGE;
	}
	
	@Override
	public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException, NotAuthorizedException, BadRequestException {
		writeData(getData().getData(), out, range);
	}

	private void writeData(byte[] data, OutputStream out, Range range) throws IOException {
		if (range != null && range.getFinish() <= data.length && (range.getFinish()-range.getStart()) < data.length)
			out.write(data, Integer.parseInt(""+range.getStart()), Integer.parseInt(""+(range.getFinish() - range.getStart())));
		else
			out.write(data);
	}
	
	@Override
	public void delete() throws NotAuthorizedException, ConflictException, BadRequestException {
		ofy.delete(getKey(getFile()), getFile().getData());
	}
	
	@Override
	public void replaceContent(InputStream in, Long length) {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			StreamUtils.readTo(in, bos);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		byte[] data  = bos.toByteArray();
		getData().setData(data);
		getFile().setBytes(data.length);
		ofy.put(getData(), getFile());
	}
	
	@Override
	public void copyTo(CollectionResource toCollection, String name) throws NotAuthorizedException, BadRequestException, ConflictException {
		if (toCollection instanceof FolderResource) {
			WebdavFile file = getFile().copy();
			file.setParent(getKey(((FolderResource) toCollection).getFolder()));
			ofy.put(file);
			return;
		}
		
		throw new BadRequestException(this, "Cannot be copied to " + toCollection.toString());
	}
	
	@Override
	public void moveTo(CollectionResource rDest, String name) throws ConflictException, NotAuthorizedException, BadRequestException {
		if (rDest instanceof FolderResource)
			if (getKey(((FolderResource)rDest).getFolder()).equals(getFile().getParent())) {
				//rename
				getFile().setName(name);
				ofy.put(getFile());
			}
			else {
				//move
				copyTo(rDest, name);
				delete();
			}
	}

	@Override
	public void setProperties(Fields fields) {
		log.info("get some fields to update/remove" + fields);
	}
	
	@Override
	public String processForm(Map<String, String> parameters, Map<String, FileItem> files) throws BadRequestException, NotAuthorizedException, ConflictException {
		throw new BadRequestException(this, "not implemented yet");
	}

	@Override
	public LockResult lock(LockTimeout timeout, LockInfo lockInfo) throws NotAuthorizedException, PreConditionFailedException, LockedException {
		return lockMan.lock(timeout, lockInfo, this);
	}

	@Override
	public LockResult refreshLock(String token) throws NotAuthorizedException, PreConditionFailedException {
		return lockMan.refresh(token, this);
	}

	@Override
	public void unlock(String tokenId) throws NotAuthorizedException, PreConditionFailedException {
		lockMan.unlock(tokenId, this);
	}

	@Override
	public LockToken getCurrentLock() {
		return lockMan.getCurrentToken(this);
	}
}
