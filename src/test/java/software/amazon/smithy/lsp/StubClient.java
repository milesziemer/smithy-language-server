package software.amazon.smithy.lsp;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

class StubClient implements LanguageClient {
    public List<PublishDiagnosticsParams> diagnostics = new ArrayList<>();
    public List<MessageParams> shown = new ArrayList<>();
    public List<MessageParams> logged = new ArrayList<>();

    public StubClient() {
    }

    public void clear() {
        this.diagnostics.clear();
        this.shown.clear();
        this.logged.clear();
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        this.diagnostics.add(diagnostics);
    }

    @Override
    public void telemetryEvent(Object object) {
        // TODO Auto-generated method stub

    }

    @Override
    public void logMessage(MessageParams message) {
        this.logged.add(message);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        this.shown.add(messageParams);
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        // TODO Auto-generated method stub
        return null;
    }
}
