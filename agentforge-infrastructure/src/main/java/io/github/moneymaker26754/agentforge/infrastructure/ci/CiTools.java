package io.github.moneymaker26754.agentforge.infrastructure.ci;

/** Shared helpers for the CI tool package. Not a Spring bean. */
final class CiTools {
    private CiTools() {}

    static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n[TRUNCATED]";
    }
}
