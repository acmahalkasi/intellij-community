package org.jetbrains.builtInWebServer;

import com.intellij.concurrency.JobScheduler;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.openapi.util.AsyncValueLoader;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.Consumer;
import com.intellij.util.net.NetUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.io.NettyUtil;

import javax.swing.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public abstract class NetService implements Disposable {
  protected static final Logger LOG = Logger.getInstance(NetService.class);

  protected final Project project;

  protected final AsyncValueLoader<OSProcessHandler> processHandler = new AsyncValueLoader<OSProcessHandler>() {
    @Override
    protected boolean isCancelOnReject() {
      return true;
    }

    @Nullable
    private OSProcessHandler doGetProcessHandler(int port) {
      try {
        return createProcessHandler(project, port);
      }
      catch (ExecutionException e) {
        LOG.error(e);
        return null;
      }
    }

    @Override
    protected void load(@NotNull final AsyncResult<OSProcessHandler> result) throws IOException {
      final int port = NetUtils.findAvailableSocketPort();
      final OSProcessHandler processHandler = doGetProcessHandler(port);
      if (processHandler == null) {
        result.setRejected();
        return;
      }

      result.doWhenRejected(new Runnable() {
        @Override
        public void run() {
          processHandler.destroyProcess();
        }
      });

      final MyProcessAdapter processListener = new MyProcessAdapter();
      processHandler.addProcessListener(processListener);
      processHandler.startNotify();

      if (result.isRejected()) {
        return;
      }

      JobScheduler.getScheduler().schedule(new Runnable() {
        @Override
        public void run() {
          if (result.isRejected()) {
            return;
          }

          ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
              if (!result.isRejected()) {
                try {
                  connectToProcess(result, port, processHandler, processListener);
                }
                catch (Throwable e) {
                  result.setRejected();
                  LOG.error(e);
                }
              }
            }
          });
        }
      }, NettyUtil.MIN_START_TIME, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void disposeResult(@NotNull OSProcessHandler processHandler) {
      try {
        closeProcessConnections();
      }
      finally {
        processHandler.destroyProcess();
      }
    }
  };

  private ConsoleView console;

  protected NetService(@NotNull Project project) {
    this.project = project;
  }

  @Nullable
  protected abstract OSProcessHandler createProcessHandler(Project project, int port) throws ExecutionException;

  protected void connectToProcess(@NotNull AsyncResult<OSProcessHandler> asyncResult, int port, @NotNull OSProcessHandler processHandler, @NotNull Consumer<String> errorOutputConsumer) {
    asyncResult.setDone(processHandler);
  }

  protected abstract void closeProcessConnections();

  @Override
  public void dispose() {
    processHandler.reset();
  }

  protected void configureConsole(@NotNull TextConsoleBuilder consoleBuilder) {
  }

  @NotNull
  protected abstract String getConsoleToolWindowId();

  @NotNull
  protected abstract Icon getConsoleToolWindowIcon();

  private final class MyProcessAdapter extends ProcessAdapter implements Consumer<String> {
    private void createConsole() {
      TextConsoleBuilder consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
      configureConsole(consoleBuilder);
      console = consoleBuilder.getConsole();

      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          ToolWindow toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(getConsoleToolWindowId(), false, ToolWindowAnchor.BOTTOM, project, true);
          toolWindow.setIcon(getConsoleToolWindowIcon());
          toolWindow.getContentManager().addContent(ContentFactory.SERVICE.getInstance().createContent(console.getComponent(), "", false));
        }
      }, project.getDisposed());
    }

    @Override
    public void onTextAvailable(ProcessEvent event, Key outputType) {
      print(event.getText(), ConsoleViewContentType.getConsoleViewType(outputType));
    }

    private void print(String text, ConsoleViewContentType contentType) {
      if (console == null) {
        createConsole();
      }
      console.print(text, contentType);
    }

    @Override
    public void processTerminated(ProcessEvent event) {
      processHandler.reset();
      print(getConsoleToolWindowId() + " terminated\n", ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    @Override
    public void consume(String message) {
      print(message, ConsoleViewContentType.ERROR_OUTPUT);
    }
  }
}