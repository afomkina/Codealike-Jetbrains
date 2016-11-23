package com.codealike.client.core.internal.services;

import java.util.Observable;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import com.codealike.client.core.internal.model.TrackedProjectManager;
import com.codealike.client.core.internal.startup.PluginContext;
import com.codealike.client.core.internal.tracking.StateTracker;
import com.codealike.client.core.internal.tracking.ActivitiesRecorder.FlushResult;
import com.codealike.client.core.internal.utils.LogManager;
//import com.codealike.client.core.internal.utils.WorkbenchUtils;
import com.google.common.collect.BiMap;

public class TrackingService extends Observable {

	public static final Duration TWO_MINUTES = Duration.standardMinutes(2);
	public static final Duration TEN_SECONDS = Duration.standardSeconds(10);
	public static final int ONE_SECOND = 1000;
	private static TrackingService _instance;
	
	private TrackedProjectManager trackedProjectManager;
	private ScheduledThreadPoolExecutor flushExecutor;
	private StateTracker tracker;
	private boolean isTracking;
	//private WorkspaceChangesListener changesListener;
	private DateTime startWorkspaceDate;
	private PluginContext context;
	
	public static TrackingService getInstance() {
		if (_instance == null) {
			_instance = new TrackingService();
		}
		if (_instance.context == null) {
			_instance.context = PluginContext.getInstance();
		}
		
		return _instance;
	}
	
	public TrackingService() {
		this.trackedProjectManager = new TrackedProjectManager();
		this.tracker = new StateTracker(/*PlatformUI.getWorkbench().getDisplay(), */ONE_SECOND, TWO_MINUTES);
		//this.changesListener = null; //new WorkspaceChangesListener();
		this.isTracking = false;
	}

	public void startTracking() {
		this.tracker.startTracking();
		
		startFlushExecutor();
		
		//We need to start tracking unassigned project for states like "debugging" which does not belong to any project.
		startTrackingUnassignedProject();
		
		this.isTracking = true;
		setChanged();
		notifyObservers();
	}

	public void trackDocumentFocus(Editor editor) {
		tracker.trackNewSelection(editor);
	}

	public void trackCodingEvent(Editor editor) {
		tracker.trackCodingEvent(editor);
	}

	private void startFlushExecutor() {
		this.flushExecutor = new ScheduledThreadPoolExecutor(1);
		Runnable idlePeriodicTask = new Runnable() {
			
			@Override
			public void run() {
				//WorkbenchUtils.addMessageToStatusBar("CodealikeApplicationComponent is sending activities...");
				FlushResult result = tracker.flush(context.getIdentityService().getIdentity(), context.getIdentityService().getToken());
				switch (result) {
				case Succeded:
					//WorkbenchUtils.addMessageToStatusBar("CodealikeApplicationComponent sent activities");
					break;
				case Skip:
					//WorkbenchUtils.addMessageToStatusBar("No data to be sent");
					break;
				case Offline:
					//WorkbenchUtils.addMessageToStatusBar("CodealikeApplicationComponent is working in offline mode");
				case Report:
					//WorkbenchUtils.addMessageToStatusBar("CodealikeApplicationComponent is storing corrupted entries for further inspection");
				}
			}
		};
		
		int flushInterval = Integer.valueOf(context.getProperty("activity-log.interval.secs"));
		this.flushExecutor.scheduleAtFixedRate(idlePeriodicTask, flushInterval, flushInterval, TimeUnit.SECONDS);
	}

	public void stopTracking(boolean propagate) {
		this.tracker.stopTracking();
		if (this.flushExecutor != null) {
			this.flushExecutor.shutdown();
		}
		
		this.trackedProjectManager.stopTracking();
		/*ResourcesPlugin.getWorkspace().removeResourceChangeListener(this.changesListener);*/
		
		this.isTracking = false;
		if (propagate) {
			setChanged();
			notifyObservers();
		}
	}

	public void enableTracking() {
		if (context.isAuthenticated()) {
			startTracking();

			Notification note = new Notification("CodealikeApplicationComponent.Notifications",
					"CodealikeApplicationComponent",
					"Codealike  is tracking your projects",
					NotificationType.INFORMATION);
			Notifications.Bus.notify(note);
		}
	}

	public void disableTracking() {
		stopTracking(true);

		Notification note = new Notification("CodealikeApplicationComponent.Notifications",
				"CodealikeApplicationComponent",
				"Codealike  is not tracking your projects",
				NotificationType.INFORMATION);
		Notifications.Bus.notify(note);
	}

	public synchronized void startTracking(Project project) {
		if (!project.isOpen()) {
			return;
		}
		if (isTracked(project)) {
			return;
		}
		UUID projectId = PluginContext.getInstance().getOrCreateUUID(project);
		if (projectId != null && trackedProjectManager.trackProject(project, projectId)) {
			tracker.startTrackingProject(project, projectId, this.startWorkspaceDate);
		}
		else {
			LogManager.INSTANCE.logWarn(String.format("Could not track project %s. "
					+ "If you have a duplicated UUID in any of your \"com.codealike.client.intellij.prefs\" please delete one of those to generate a new UUID for"
					+ "that project", project.getName()));
		}
	}
	
	private void startTrackingUnassignedProject() {
		try {
			PluginContext.getInstance().registerProjectContext(PluginContext.UNASSIGNED_PROJECT, "Unassigned");
		} catch (Exception e) {
			LogManager.INSTANCE.logWarn("Could not track unassigned project.");
		}
	}

	public boolean isTracked(Project project) {
		return this.trackedProjectManager.isTracked(project);
	}

	public void stopTracking(Project project) {
		this.trackedProjectManager.stopTrackingProject(project);
	}

	public TrackedProjectManager getTrackedProjectManager() {
		return this.trackedProjectManager;
	}

	public UUID getUUID(Project project) {
		return this.trackedProjectManager.getTrackedProjectId(project);
	}

	public Project getProject(UUID projectId) {
		return this.trackedProjectManager.getTrackedProject(projectId);
	}

	public BiMap<Project, UUID> getTrackedProjects() {
		return this.trackedProjectManager.getTrackedProjects();
	}

	public void setBeforeOpenProjectDate() {
		this.startWorkspaceDate = DateTime.now();
	}

	public boolean isTracking() {
		return this.isTracking;
	}

	public void flushRecorder(final String identity, final String token) {
		if (this.isTracking) {
			this.flushExecutor.execute( new Runnable() {
				
				@Override
				public void run() {
					tracker.flush(identity, token);
				}
			});
		}
	}
	
}
