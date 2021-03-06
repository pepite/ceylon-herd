package controllers;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import models.Module;
import models.ModuleVersion;
import models.User;
import notifiers.Emails;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import play.data.validation.Required;
import play.data.validation.Validation;
import util.Util;

public class LoggedInRepo extends LoggedInController {

	private static ModuleVersion getModuleVersion(Long id) {
		if(validationFailed())
			Repo.index();
		models.ModuleVersion moduleVersion = models.ModuleVersion.findById(id);
		if(moduleVersion == null){
			Validation.addError(null, "Unknown module version");
			prepareForErrorRedirect();
			Repo.index();
		}
		return moduleVersion;
	}

	private static Module getModule(Long id) {
		if(id == null){
			Validation.addError(null, "Module id required");
			prepareForErrorRedirect();
			Repo.index();
		}
		models.Module module = models.Module.findById(id);
		if(module == null){
			Validation.addError(null, "Unknown module");
			prepareForErrorRedirect();
			Repo.index();
		}
		User user = getUser();
		if(!module.canEdit(user)){
			Validation.addError(null, "Unauthorised");
			prepareForErrorRedirect();
			Repo.index();
		}
		return module;
	}

	public static void editForm(@Required Long id){
		Module module = getModule(id);
		
		render(module);
	}


	public static void edit(@Required Long id, String url, String issues, String code, String friendlyName){
		Module module = getModule(id);
		
		module.codeURL = code;
		module.homeURL = url;
		module.issueTrackerURL = issues;
		module.friendlyName = friendlyName;
		module.save();
		
		Repo.versions(module.id);
	}

	public static void userList(String term){
		List<String> userNames = Collections.emptyList();
		if(StringUtils.isEmpty(term))
			renderJSON(userNames);
		
		term = term.toLowerCase();
		userNames = User.find("SELECT userName FROM User WHERE LOCATE(?, LOWER(userName)) <> 0", term).fetch(10);
		
		renderJSON(userNames);
	}
	
	public static void permissionsForm(@Required Long id){
		Module module = getModule(id);
		
		render(module);
	}

	public static void addAdmin(@Required Long id, String userName){
		Module module = getModule(id);
		User user = getUser(userName);
		if(user == null) // error
			permissionsForm(id);
		
		if(module.admins.contains(user) || module.owner.equals(user)){
			flash("message", "User already admin on this module");
			permissionsForm(id);
		}
		
		module.admins.add(user);
		module.save();
		
		Emails.addAdminNotification(module, user, getUser());
		
		flash("message", "User "+user.userName+" added as admin on this project");
		permissionsForm(id);
	}

	private static User getUser(String userName) {
		if(StringUtils.isEmpty(userName)){
			Validation.addError("userName", "User required");
			prepareForErrorRedirect();
			return null;
		}
		User user = User.findRegisteredByUserName(userName);
		if(user == null){
			Validation.addError("userName", "User unknown");
			prepareForErrorRedirect();
			return null;
		}
		return user;
	}

	public static void removeAdmin(@Required Long id, Long userId){
		Module module = getModule(id);
		if(userId == null){
			Validation.addError("userName", "User required");
			prepareForErrorRedirect();
			permissionsForm(id);
		}
		User user = User.findById(userId);
		if(user == null){
			Validation.addError("userName", "User unknown");
			prepareForErrorRedirect();
			permissionsForm(id);
		}
		
		if(!module.admins.contains(user)){
			flash("message", "User not admin on this module");
			permissionsForm(id);
		}
		
		module.admins.remove(user);
		module.save();
		
		flash("message", "User "+user.userName+" is not admin anymore on this project");
		permissionsForm(id);
	}

	public static void transferForm(Long id){
		Module module = getModule(id);
		checkModuleOwner(module);

		render(module);
	}
	
	private static void checkModuleOwner(Module module) {
		// restricted
		if(!module.isOwnedBy(getUser())){
			Validation.addError(null, "Unauthorised");
			prepareForErrorRedirect();
			LoggedInRepo.editForm(module.id);
		}
	}

	public static void transfer(Long id, String userName){
		Module module = getModule(id);
		checkModuleOwner(module);
		
		User newOwner = getUser(userName);
		if(newOwner == null) // error
			transferForm(id);
		
		if(module.owner.equals(newOwner)){
			flash("message", "User already owns this module");
			transferForm(id);
		}
		
		models.Project project = models.Project.findOwner(module.name);
		Project.transferOwnership(project.id, newOwner.id);
	}
	
	@Check("admin")
	public static void remove1(@Required Long moduleId, @Required Long versionId){
		ModuleVersion moduleVersion = getModuleVersion(versionId);
		Module module = moduleVersion.module;
		
		render(module, moduleVersion);
	}
	
	@Check("admin")
	public static void remove2(@Required Long moduleId, @Required Long versionId){
		ModuleVersion moduleVersion = getModuleVersion(versionId);
		Module module = moduleVersion.module;
		
		render(module, moduleVersion);
	}
	
	@Check("admin")
	public static void remove3(@Required Long moduleId, @Required Long versionId) throws IOException{
		ModuleVersion moduleVersion = getModuleVersion(versionId);
		
		String path = moduleVersion.getPath();
		File repoDir = Util.getRepoDir();
		File moduleDir = new File(repoDir, path);
		FileUtils.deleteDirectory(moduleDir);
		
		moduleVersion.delete();
		
		Repo.index();
	}

}