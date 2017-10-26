package com.codealike.client.intellij;

import com.codealike.client.core.internal.services.IdentityService;
import com.codealike.client.core.internal.services.TrackingService;
import com.codealike.client.core.internal.startup.PluginContext;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.joda.time.DateTime;

/**
 * Created by Daniel on 11/16/2016.
 */
public class CodealikeProjectComponent implements ProjectComponent {
    private Project _project = null;

    public CodealikeProjectComponent(Project project) {
        _project = project;
    }

    @Override
    public void initComponent() {
        // TODO: insert component initialization logic here
    }

    @Override
    public void disposeComponent() {
        // TODO: insert component disposal logic here
    }

    @Override
    @NotNull
    public String getComponentName() {
        return "CodealikeProjectComponent";
    }

    @Override
    public void projectOpened() {
        // called when project is opened
        PluginContext pluginContext = PluginContext.getInstance();
        TrackingService trackingService = pluginContext.getTrackingService();
        IdentityService identityService = pluginContext.getIdentityService();
        if (identityService.isAuthenticated()) {
            switch(identityService.getTrackActivity()) {
                case Always:
                {
                    trackingService.enableTracking();
                    trackingService.startTracking(_project, DateTime.now());
                    break;
                }
                case AskEveryTime:
                case Never:
                    Notification note = new Notification("CodealikeApplicationComponent.Notifications",
                            "Codealike",
                            "Codealike  is not tracking your projects",
                            NotificationType.INFORMATION);
                    Notifications.Bus.notify(note);
                    break;
            }
        }
        else {
            trackingService.disableTracking();
        }
    }

    @Override
    public void projectClosed() {
        TrackingService trackingService = TrackingService.getInstance();

        // called when project is being closed
        if (trackingService.isTracking()) {
            trackingService.stopTracking(_project);

            // only disable tracking when last project gets closed
            if (trackingService.getTrackedProjects().values().isEmpty())
                trackingService.disableTracking();
        }
    }
}
