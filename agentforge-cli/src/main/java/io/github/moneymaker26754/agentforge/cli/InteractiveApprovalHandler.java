package io.github.moneymaker26754.agentforge.cli;

import io.github.moneymaker26754.agentforge.core.ApprovalDecision;
import io.github.moneymaker26754.agentforge.core.ApprovalHandler;
import io.github.moneymaker26754.agentforge.core.ApprovalRequest;
import java.util.Locale;
import java.util.Scanner;

/** Interactive console approval used by CLI runs; the server uses a persisted pending approval instead. */
public final class InteractiveApprovalHandler implements ApprovalHandler {
    @Override
    public ApprovalDecision decide(ApprovalRequest request) {
        System.err.printf("Approve %s (%s)? [y/N] ", request.invocation().call().name(), request.reason());
        String answer = new Scanner(System.in).nextLine().trim().toLowerCase(Locale.ROOT);
        return answer.equals("y") || answer.equals("yes") ? ApprovalDecision.APPROVE_ONCE : ApprovalDecision.REJECT;
    }
}
