package com.spdx.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spdx.api.ApiInterface;
import com.spdx.api.CalloutApi;
import com.spdx.model.ApiResult;
import com.spdx.model.HeaderFileNumber;
import com.spdx.model.OutputModel;
import com.spdx.model.ResponseApiComponent;
import com.spdx.model.ResponseToken;
import com.spdx.model.SpdxComponent;
import com.spdx.model.SpdxLicense;
import com.spdx.model.SpdxRelease;
import com.spdx.utils.SpdxUtils;

@Service
public class FileStorageImpl implements FileStoreService {

	private final String rootPathFile = "/home/sw360/spdx/";
	Logger log = LoggerFactory.getLogger(this.getClass().getName());
	private final Path rootLocation = Paths.get(rootPathFile);
	private static ObjectMapper mapper = new ObjectMapper();
	private static String FULLNAME = "\"fullName\": ";
	private static String FILE = "FILE: ";

	private static ApiInterface callout = new CalloutApi();

	@Override
	public void store(MultipartFile file) throws IOException {
		InputStream stream = file.getInputStream();
		try {
			Files.copy(stream, this.rootLocation.resolve(file.getOriginalFilename()),
					StandardCopyOption.REPLACE_EXISTING);
			stream.close();
		} catch (Exception e) {
			stream.close();
			throw new RuntimeException("FAIL! -> message = " + e.getMessage());
		}
	}

	@Override
	public void init() {
		try {
			File drictory = new File(rootPathFile);
			if(!drictory.exists())
				Files.createDirectory(rootLocation);
		} catch (IOException e) {
			throw new RuntimeException("Could not initialize storage!");
		}
	}

	@Override
	public OutputModel readContentFile(String fileName) throws Exception {
		File file = new File(rootPathFile+fileName);
		List<String> messages = new ArrayList<String>();
		if(!fileName.toLowerCase().contains(".spdx")) {
			messages.add("File import not SPDX file, please choose other files");
			if(file.exists())
				file.delete();
			return new OutputModel(fileName, messages);
		}
		ResponseToken token = callout.getToken();
		if (token.getAccess_token() == null)
			messages.add("Authentication errors");
		else {
			HeaderFileNumber fileNumber = new HeaderFileNumber();
			ResponseApiComponent componentResponse = postComponentApi(fileName, messages, token);
			int countLicenseInrelease = postReleaseApi(fileName, messages, componentResponse, token);
			postLicenseApi(fileName, messages, componentResponse, token, countLicenseInrelease);
			if(file.exists())
				file.delete();
		}
		return new OutputModel(fileName, messages);
	}

	private ResponseApiComponent postComponentApi(String fileName, List<String> messages, ResponseToken token)
			throws Exception {
		String pathFile = rootPathFile + fileName;
		
		SpdxComponent component = SpdxUtils.importComponent(pathFile);
		String compId = getComponentIdByComponentName(component.getName(), token);
		ApiResult result = new ApiResult();
//		if (component.getSpdxVersion() == null) {
//			messages.add("File import errors, beacause Component is empty");
//			return new ResponseApiComponent(false, null, component.getName());
//		} else {
//			if (!component.getSpdxVersion().equals("SPDX-2.1"))
//				messages.add(
//						FILE + fileName + ": SPDX version of file is " + component.getSpdxVersion() + " not SPDX-2.1, SPDX file will import, but it can have some errors");
			try {
				String requestBody = mapper.writeValueAsString(component);
				if(compId!= null) {
					result = callout.updateComponent(compId, requestBody, token);
					if(result.getResponseCode() == 200 || result.getResponseCode() == 201) {
						messages.add("Update component " +component.getName()+ " success");
						removeReleaseFromComponent(compId,token);
						return new ResponseApiComponent(true, compId, component.getName());
					} else {
						messages.add((new JSONObject(result.getBody())).getString("message"));
						return new ResponseApiComponent(false, null, component.getName());
					}
				} else {
					result = callout.postComponent(requestBody, token);
					if (result.getResponseCode() == 200 || result.getResponseCode() == 201) {
						messages.add("Import new component success");
						String componentId = (new JSONObject(result.getBody())).getJSONObject("_links")
								.getJSONObject("self").getString("href");
						return new ResponseApiComponent(true, componentId.substring(componentId.lastIndexOf("/") + 1),
								component.getName());
					} else {
						messages.add((new JSONObject(result.getBody())).getString("message"));
						return new ResponseApiComponent(false, null, component.getName());
					}
				}
				
			} catch (Exception e) {
				messages.add("Import component error");
				e.printStackTrace();
				return new ResponseApiComponent(false, null, component.getName());
			}
//		}
	}

	private void postLicenseApi(String fileName, List<String> messages, ResponseApiComponent responseComp,
			ResponseToken token, int countLicenseInRelease) throws Exception {
		if (!responseComp.getResponseFlg())
			messages.add("Not import License because Component import errors");
		else {
			String pathFile = rootPathFile + fileName;
			List<SpdxLicense> lstLicense = SpdxUtils.importLicense(pathFile);
			if (lstLicense.size() == 0 || lstLicense == null)
				messages.add("Not License is imported because License is empty");
			else {
				for (SpdxLicense license : lstLicense) {
					String requestBody = mapper.writeValueAsString(license);
					try {
						ApiResult res = callout.postLicense(requestBody, token);
						if (res.getResponseCode() == 200 || res.getResponseCode() == 201)
							countLicenseInRelease++;
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				messages.add("Import success " + (countLicenseInRelease) + " licenses");
			}
		}
	}

	private int postReleaseApi(String fileName, List<String> messages, ResponseApiComponent responseComp,
			ResponseToken token) throws Exception {
		int countLicense = 0;
		if (!responseComp.getResponseFlg())
			messages.add("Not import Release because Component import errors");
		else {
			String pathFile = rootPathFile + fileName;
			List<SpdxRelease> lstRelease = SpdxUtils.importRelease(pathFile);
			if (lstRelease.size() == 0 || lstRelease == null)
				messages.add(" No Release is imported because Release is empty");
			else {
				
				int count = 0;
				ApiResult resListLicense = callout.getListLicense(token);
				for (SpdxRelease release : lstRelease) {
					List<String> lstLicen = new ArrayList<String>();
					if (!resListLicense.getBody().contains(FULLNAME + release.getLicenseInfoInFile())) {
						if (!release.getLicenseInfoInFile().isEmpty()) {
							String[] lstLicense = release.getLicenseInfoInFile().split("&");
							for (String item : lstLicense) {
								if (item.trim().equals("NOASSERTION"))
									continue;
								lstLicen.add(item);
								ApiResult res = callout.postLicense(requestLicenseForRlease(item), token);
								if (res.getResponseCode() == 200 || res.getResponseCode() == 201)
									countLicense++;
							}

						}
					}
					release.setMainLicenseIds(
							lstLicen.stream().filter(item -> !"NOASSERTION".equals(item)).collect(Collectors.toList()));
					release.setComponentId(responseComp.getComponentId());
					release.setVersion(release.getSpdxId().trim());
					release.setName(responseComp.getComponentName());
					
					String requestBody = mapper.writeValueAsString(release);
					ApiResult res = callout.postRelease(requestBody, token);
					if (res.getResponseCode() == 200 || res.getResponseCode() == 201)
						count++;
				}
				messages.add("Import success " + count + " releases");
			}
		}
		return countLicense;
	}
	
	private String requestLicenseForRlease(String name) throws Exception {
		SpdxLicense license = new SpdxLicense();
		license.setFullName(name);
		license.setShortName(name);
		return mapper.writeValueAsString(license);
	}
	
	private String getComponentIdByComponentName(String name, ResponseToken token) throws Exception {
		ApiResult res = callout.getComponentIdByName(name, token);
		if(res.getResponseCode() != 200 && res.getResponseCode() != 201)
			return null;
		if(res.getBody().equals("{ }"))
			return null;
		JSONArray component = (new JSONObject(res.getBody())).getJSONObject("_embedded")
				.getJSONArray("sw360:components");
		for (Object object : component) {
			if(object instanceof JSONObject) {
				if(!((JSONObject) object).getString("name").equals(name))
					return null;
				else {
					String link = ((JSONObject) object).getJSONObject("_links").getJSONObject("self").getString("href");
					return link.substring(link.lastIndexOf("/")+1);
				}
			}
		}
		return null;
	}
	
	private void removeReleaseFromComponent(String compId, ResponseToken token) throws Exception {
		ApiResult res = callout.getComponentById(compId, token);
		List<String> lstRele = new ArrayList<String>();
		if(res.getResponseCode() == 200 || res.getResponseCode() == 201) {
			JSONArray lstRelease = (new JSONObject(res.getBody())).getJSONObject("_embedded").getJSONArray("sw360:releases");
			for (Object object : lstRelease) {
				if (object instanceof JSONObject)
					lstRele.add(((JSONObject) object).getJSONObject("_links").getJSONObject("self").getString("href"));
			}
			for (String api : lstRele)
				callout.deleteRelease(api, token);
		}
	}
}
