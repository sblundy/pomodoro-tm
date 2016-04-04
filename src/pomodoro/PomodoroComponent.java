/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pomodoro;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerAdapter;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.NotNull;
import pomodoro.modalwindow.ModalDialog;
import pomodoro.model.ControlThread;
import pomodoro.model.PomodoroModel;
import pomodoro.model.PomodoroModelState;
import pomodoro.model.Settings;
import pomodoro.toolkitwindow.PomodoroToolWindows;

import javax.swing.*;
import java.applet.Applet;
import java.applet.AudioClip;
import java.awt.*;

/**
 * User: dima
 * Date: May 30, 2010
 */
public class PomodoroComponent implements ApplicationComponent {
	private ControlThread controlThread;
	private PomodoroModel model;

	@Override
	public void initComponent() {
		Settings settings =  getSettings();

		model = new PomodoroModel(settings, ServiceManager.getService(PomodoroModelState.class));

		PomodoroToolWindows toolWindows = new PomodoroToolWindows();
		settings.setChangeListener(toolWindows);

		new UserNotifier(settings, model);

		ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerAdapter() {
			@Override
			public void projectOpened(Project project) {
				StatusBar statusBar = statusBarFor(project);
				statusBar.addWidget(new PomodoroWidget(), "before Position", project);
			}
		});

		controlThread = new ControlThread(model);
		controlThread.start();
	}

	public static Settings getSettings() {
		return ServiceManager.getService(Settings.class);
	}

	@Override
	public void disposeComponent() {
		controlThread.shouldStop();
	}

	@NotNull
	@Override
	public String getComponentName() {
		return "Pomodoro";
	}

	public PomodoroModel getModel() {
		return model;
	}

	private static StatusBar statusBarFor(Project project) {
		return WindowManager.getInstance().getStatusBar(project);
	}


	private static class UserNotifier {
		// TODO sound playback seems to be slow for the first time
		private final AudioClip ringSound1 = Applet.newAudioClip(getClass().getResource("/resources/ring.wav"));
		private final AudioClip ringSound2 = Applet.newAudioClip(getClass().getResource("/resources/ring2.wav"));
		private final AudioClip ringSound3 = Applet.newAudioClip(getClass().getResource("/resources/ring3.wav"));
		private ModalDialog modalDialog;

		public UserNotifier(final Settings settings, final PomodoroModel model) {
			model.addUpdateListener(this, new Runnable() {
				@Override
				public void run() {
					switch (model.getState()) {
						case STOP:
							if (model.getLastState() == PomodoroModel.PomodoroState.BREAK && !model.wasManuallyStopped()) {
								playRingSound(settings.getRingVolume());
								if (settings.isBlockDuringBreak()) unblockIntelliJ();
							}
							break;
						case BREAK:
							if (model.getLastState() != PomodoroModel.PomodoroState.BREAK) {
								playRingSound(settings.getRingVolume());
								if (settings.isPopupEnabled()) showPopupNotification();
								if (settings.isBlockDuringBreak()) blockIntelliJ();
							}
							break;
					}
				}
			});
		}

		private void blockIntelliJ() {
			ApplicationManager.getApplication().invokeLater(new Runnable() {
				@Override
				public void run() {
					DataContext dataContext = DataManager.getInstance().getDataContext(IdeFocusManager.getGlobalInstance().getFocusOwner());
					Project project = PlatformDataKeys.PROJECT.getData(dataContext);
					Window window = WindowManager.getInstance().getFrame(project);

					modalDialog = new ModalDialog(window);
					modalDialog.show();
				}
			});
		}

		private void unblockIntelliJ() {
			if (modalDialog == null) return; // can happen if user turns on this option during break
			modalDialog.hide();
		}

		private void playRingSound(int ringVolume) {
			switch (ringVolume) {
				case 0:
					// ring is disabled
					break;
				case 1:
					ringSound1.play();
					break;
				case 2:
					ringSound2.play();
					break;
				case 3:
					ringSound3.play();
					break;
				default:
					throw new IllegalStateException();
			}
		}

		private void showPopupNotification() {
			SwingUtilities.invokeLater(new Runnable() {
				@Override
				public void run() {
					DataContext dataContext = DataManager.getInstance().getDataContextFromFocus().getResult();
					Project project = PlatformDataKeys.PROJECT.getData(dataContext);
					if (project == null) return;

					String statusMessage = UIBundle.message("notification.text");

					ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
					if (hasPomodoroToolWindow(toolWindowManager)) {
						toolWindowManager.notifyByBalloon(PomodoroToolWindows.TOOL_WINDOW_ID, MessageType.INFO, statusMessage);
					} else {
						toolWindowManager.notifyByBalloon("Project", MessageType.INFO, statusMessage);
					}
				}

				private boolean hasPomodoroToolWindow(ToolWindowManager toolWindowManager) {
					for (String id : toolWindowManager.getToolWindowIds()) {
						if (PomodoroToolWindows.TOOL_WINDOW_ID.equals(id)) {
							return true;
						}
					}
					return false;
				}
			});
		}
	}
}
