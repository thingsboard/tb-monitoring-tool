/**
 * Copyright © 2016-2018 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.tools.service.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

@Service
public class EmailService {

    private final static String USER_NAME = "dlandiak2110@gmail.com";
    private final static String PASSWORD = "qaZ3380570qaZ";

    @Value("${email.alertEmails}")
    private String alertEmails;

    @Value("${email.statusEmail}")
    private String statusEmail;

    private Session session;

    @PostConstruct
    void init() {
        Properties props = new Properties();
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");

        session = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(USER_NAME, PASSWORD);
            }
        });
    }

    public void sendAlertEmail() {
        try {
            Transport.send(createMessage(alertEmails, "TB Status", "TB is currently down or in bad conditions!"));
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    public void sendStatusEmail() {
        try {
            Transport.send(createMessage(statusEmail, "TB Script Status", "Script is working well!"));
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    private Message createMessage(String emailAddresses, String subject, String text) throws MessagingException {
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(USER_NAME));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailAddresses));
        message.setSubject(subject);
        message.setText(text);
        return message;
    }

}
