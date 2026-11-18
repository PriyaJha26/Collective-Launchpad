package com.collectivelaunchpad.mail;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.collectivelaunchpad.config.AppProperties;
import com.collectivelaunchpad.domain.EmailLog;
import com.collectivelaunchpad.domain.Repositories.EmailLogRepository;

/**
 * Sends email from the group's one mailbox (Titan, e.g. hello@ourvillagecrafts.in) over SMTP.
 * Without SMTP settings, emails are recorded as SIMULATED so the demo still works.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    public record Result(boolean ok, String detail) {}

    private final ObjectProvider<JavaMailSender> sender;
    private final EmailLogRepository emailLog;
    private final String from;
    private final String smtpHost;

    public MailService(ObjectProvider<JavaMailSender> sender, EmailLogRepository emailLog, AppProperties props,
                       @Value("${spring.mail.host:}") String smtpHost) {
        this.sender = sender;
        this.emailLog = emailLog;
        this.from = props.mailFrom();
        this.smtpHost = smtpHost;
    }

    public String from() {
        return from;
    }

    public boolean smtpConfigured() {
        return smtpHost != null && !smtpHost.isBlank() && sender.getIfAvailable() != null;
    }

    public String mode() {
        return smtpConfigured() ? "SMTP via " + smtpHost : "Simulated (no SMTP settings)";
    }

    public Result send(String to, String replyTo, String subject, String body) {
        EmailLog entry = new EmailLog();
        entry.fromAddress = from;
        entry.toAddress = to;
        entry.replyTo = replyTo;
        entry.subject = subject;
        entry.body = body;
        Result result;
        if (!smtpConfigured()) {
            log.info("[simulated email] {} -> {}: {}", from, to, subject);
            result = new Result(true, "SIMULATED");
        } else {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(from);
                msg.setTo(to);
                if (replyTo != null && !replyTo.isBlank()) {
                    msg.setReplyTo(replyTo);
                }
                msg.setSubject(subject);
                msg.setText(body);
                sender.getObject().send(msg);
                result = new Result(true, "SENT");
            } catch (Exception e) {
                log.warn("Email to {} failed: {}", to, e.getMessage());
                result = new Result(false, "FAILED: " + e.getMessage());
            }
        }
        entry.result = result.detail();
        emailLog.save(entry);
        return result;
    }

    public List<EmailLog> recent() {
        return emailLog.findTop10ByOrderByIdDesc();
    }
}
