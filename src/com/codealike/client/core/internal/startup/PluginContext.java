package com.codealike.client.core.internal.startup;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import com.codealike.client.intellij.ProjectConfig;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;
import com.intellij.util.PlatformUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;

import com.codealike.client.core.api.ApiClient;
import com.codealike.client.core.api.ApiResponse;
import com.codealike.client.core.internal.dto.SolutionContextInfo;
import com.codealike.client.core.internal.dto.Version;
import com.codealike.client.core.internal.serialization.JodaPeriodModule;
import com.codealike.client.core.internal.services.IdentityService;
import com.codealike.client.core.internal.services.TrackingService;
import com.codealike.client.core.internal.tracking.code.ContextCreator;
import com.codealike.client.core.internal.utils.LogManager;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

@SuppressWarnings("restriction")
public class PluginContext {
	public static final String VERSION = "1.5.0.15";
	private static final String PLUGIN_PREFERENCES_QUALIFIER = "com.codealike.client.intellij";
	private static PluginContext _instance;

	private String ideName;
	private Version protocolVersion;
	private Properties properties;
	private ObjectWriter jsonWriter;
	private ObjectMapper jsonMapper;
	private ContextCreator contextCreator;

	private DateTimeFormatter dateTimeFormatter;
	private DateTimeFormatter dateTimeParser;
	private IdentityService identityService;
	private TrackingService trackingService;
	private String instanceValue;
	private File trackerFolder;
	private String machineName;
	
	public static final UUID UNASSIGNED_PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000001");
	
	public static PluginContext getInstance() {
		return PluginContext.getInstance(null);
	}
	
	public static PluginContext getInstance(Properties properties) {
			if (_instance == null)
			{
				_instance = new PluginContext(properties);
			}
		return _instance;
	}
	
	public PluginContext(Properties properties) {
		DateTimeZone.setDefault(DateTimeZone.UTC);

		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JodaPeriodModule());
		mapper.setSerializationInclusion(Include.NON_NULL);
		this.jsonWriter = mapper.writer().withDefaultPrettyPrinter();
		this.jsonMapper = mapper;
		this.contextCreator = new ContextCreator();
		this.dateTimeParser = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
		this.dateTimeFormatter = new DateTimeFormatterBuilder().appendYear(4, 4).appendLiteral("-").
				appendMonthOfYear(2).appendLiteral("-").appendDayOfMonth(2).
				appendLiteral("T").appendHourOfDay(2).appendLiteral(":").
				appendMinuteOfHour(2).appendLiteral(":").appendSecondOfMinute(2).
				appendLiteral(".").appendMillisOfSecond(3).appendLiteral("Z").toFormatter();
		this.identityService = IdentityService.getInstance();
		this.instanceValue = String.valueOf(new Random(DateTime.now().getMillis()).nextInt(Integer.MAX_VALUE) + 1);
		this.protocolVersion = new Version(0, 9);
		this.properties = properties;
		this.ideName = PlatformUtils.getPlatformPrefix();
		this.machineName = findLocalHostNameOr("unknown");
	}

	public String getIdeName() {
		return this.ideName;
	}

	public String getPluginVersion() {
		return VERSION;
	}

	public String getMachineName() {
		return machineName;
	}


	public String getHomeFolder() {
		String localFolder=null;
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			localFolder = System.getenv("APPDATA");
		}
		else {
			localFolder = System.getProperty("user.home");
		}
		return localFolder+File.separator;
	}

	public void initializeContext() throws IOException {
		this.trackingService = TrackingService.getInstance();

		trackerFolder = new File(getHomeFolder() + getActivityLogLocation());
		if (!trackerFolder.exists()) {
			trackerFolder.mkdirs();
		}
	}

	private UUID tryGetLegacySolutionId(Project project) {
		UUID solutionId = null;

		try {
			PropertiesComponent projectNode = PropertiesComponent.getInstance(project);
			String solutionIdString = projectNode.getValue("codealike.solutionId", "");

			if (solutionIdString != "") {
				solutionId = UUID.fromString(solutionIdString);
			}
		}
		catch(Exception e) {
			String projectName = project != null ? project.getName() : "";
			LogManager.INSTANCE.logError(e, "Could not retrieve solution id from legacy store " + projectName);
		}

		return solutionId;
	}

	public UUID getOrCreateUUID(Project project) {
		UUID solutionId = null;

		try {
			// load project configuration
			ProjectConfig config = ProjectConfig.getInstance(project);

			// get solution id from codealike.xml file
			// saved inside .idea folder
			solutionId = config.getProjectId();

			// if solution id is not present in codealike.xml
			if (solutionId == null) {
				// try to get solution id from legacy store
				// first versions of the plugin saved solutionId
				// in a local non romeable store
				solutionId = tryGetLegacySolutionId(project);

				// if solutionId is still null
				// there is no clue of the solution id
				// so it should be a new one. Let's create
				// a new solutionId
				if (solutionId == null) {
					solutionId = tryCreateUniqueId();

					// once created, let's register the solution id for the project
					if (!registerProjectContext(solutionId, project.getName())) {
						return null;
					}
				}

				// persist solution id in codealike.xml file
				// so it is available in the future and
				// it is saved in a romeable place
				config.setProjectId(solutionId);
			}
		} catch (Exception e) {
			String projectName = project != null ? project.getName() : "";
			LogManager.INSTANCE.logError(e, "Could not create UUID for project "+projectName);
		}

		return solutionId;
	}

	private String findLocalHostNameOr(String defaultName) {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) { //see: http://stackoverflow.com/a/40702767/1117552
			return defaultName;
		}
	}

	private String getActivityLogLocation() {
		return getProperty("activity-log.path").replace(".", File.separator);
	}
	
	private UUID tryCreateUniqueId() {
		UUID solutionId = UUID.randomUUID();
		ApiClient client;
		try {
			client = ApiClient.tryCreateNew(this.identityService.getIdentity(), this.identityService.getToken());
		}
		catch (KeyManagementException e) {
			LogManager.INSTANCE.logError(e, "Could not create unique Id synchronized with the server. There was a problem with SSL configuration.");
			return solutionId;
		}
		ApiResponse<SolutionContextInfo> response = client.getSolutionContext(solutionId);
		if (response.connectionTimeout()) {
			LogManager.INSTANCE.logInfo("Communication problems running in offline mode.");
			return solutionId;
		}
		int numberOfRetries = 0;
		while (response.conflict() || (response.error() && numberOfRetries < ApiClient.MAX_RETRIES)) {
			solutionId = UUID.randomUUID();
			response = client.getSolutionContext(solutionId);
		}
		
		return solutionId;
	}
	
	public boolean registerProjectContext(UUID solutionId, String projectName) throws Exception {
		ApiClient client;
		try {
			client = ApiClient.tryCreateNew(this.identityService.getIdentity(), this.identityService.getToken());
		}
		catch (KeyManagementException e) {
			LogManager.INSTANCE.logError(e, "Could not register unique project context in the remote server. There was a problem with SSL configuration.");
			return false;
		}
		ApiResponse<SolutionContextInfo> solutionInfoResponse = client.getSolutionContext(solutionId);
		if (solutionInfoResponse.notFound()) {
			ApiResponse<Void> response = client.registerProjectContext(solutionId, projectName);
			if (!response.success()) {
				LogManager.INSTANCE.logError("Problem registering solution.");
			}
			else {
				return true;
			}
		}
		else if (solutionInfoResponse.success()) {
			return true;
		}
		else if (solutionInfoResponse.connectionTimeout()) {
			LogManager.INSTANCE.logInfo("Communication problems running in offline mode.");
		}
		return false;
	}
	
	public boolean checkVersion() {
		ApiClient client;
		try {
			client = ApiClient.tryCreateNew();
		}
		catch (KeyManagementException e)
		{
			LogManager.INSTANCE.logError(e, "Could not access remote server. There was a problem with SSL configuration.");
			return false;
		}
		
		ApiResponse<Version> response = client.version();
		if (response.success()) {
			Version version = response.getObject();
			Version expectedVersion = getProtocolVersion();
			if (expectedVersion.getMajor() < version.getMajor()) {
				showIcompatibleVersionDialog();
				return false;
			}
			if (expectedVersion.getMinor() < version.getMinor()) {
				showIcompatibleVersionDialog();
				return false;
			}
			
			return true;
		}
		else if (!response.connectionTimeout()) {
			LogManager.INSTANCE.logError(String.format("Couldn't check plugin version (Status code=%s)", response.getStatus()));
			
			String title = "Houston... I have the feeling we messed up the specs.";
			String text = "If the problem continues, radio us for assistance.";
			/*if (PlatformUI.getWorkbench()!=null) {
				ErrorDialogView dialog = new ErrorDialogView(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), title, text, "Roger that.", "images/LunarCat.png");
				dialog.open();
			}*/
			
			return false;
		}
		return true;
	}
	
	private void showIcompatibleVersionDialog() {
		String title = "This version is not updated";
		String text = "Click below to be on the bleeding edge and enjoy an improved version of CodealikeApplicationComponent.";
		/*ErrorDialogView dialog = new ErrorDialogView(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(), title, text, "Download the latest.", "images/bigCodealike.jpg",
			new Runnable() {
				
				@Override
				public void run() {
					try {
						PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(new URL(PluginContext.getInstance().getProperty("codealike.server.url")+"/Public/Home/Download"));
					} catch (Exception e) {
						LogManager.INSTANCE.logError(e, "Couldn't open browser to download new version of plugin.");
					}
				}
		});
		dialog.open();*/
	}
	
	public String getProperty(String key) {
		return this.properties.getProperty(key);
	}
	
	public ObjectWriter getJsonWriter() {
		return this.jsonWriter;
	}
	
	public ObjectMapper getJsonMapper() {
		return this.jsonMapper;
	}

	public ContextCreator getContextCreator() {
		return this.contextCreator;
	}

	public DateTimeFormatter getDateTimeFormatter() {
		return this.dateTimeFormatter;
	}

	public DateTimeFormatter getDateTimeParser() {
		return this.dateTimeParser;
	}

	public IdentityService getIdentityService() {
		return identityService;
	}

	public boolean isAuthenticated() {
		return this.identityService.isAuthenticated();
	}

	public TrackingService getTrackingService() {
		return trackingService;
	}

	public String getInstanceValue() {
		return instanceValue;
	}

	public File getTrackerFolder() {
		return trackerFolder;
	}

	public Version getProtocolVersion() {
		return protocolVersion;
	}
}
