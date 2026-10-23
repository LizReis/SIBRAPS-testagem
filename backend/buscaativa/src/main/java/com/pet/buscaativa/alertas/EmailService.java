package com.pet.buscaativa.alertas;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    // ObjectProvider permite a aplicação subir mesmo sem SMTP configurado.
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${app.alertas.email.habilitado:false}")
    private boolean habilitado;

    @Value("${app.alertas.email.remetente:nao-responda@buscaativa.local}")
    private String remetente;

    public void enviarHtml(String para, String assunto, String corpoHtml) {
        if (!habilitado) {
            log.info("E-mail desabilitado (app.alertas.email.habilitado=false). Simulando envio para {} | assunto: {}",
                    para, assunto);
            return;
        }

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new MailPreparationException("JavaMailSender não configurado. Defina spring.mail.host.");
        }

        try {
            MimeMessage mensagem = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mensagem, false, "UTF-8");
            helper.setFrom(remetente);
            helper.setTo(para);
            helper.setSubject(assunto);
            helper.setText(corpoHtml, true);
            sender.send(mensagem);
        } catch (MessagingException e) {
            throw new MailPreparationException("Falha ao montar o e-mail para " + para, e);
        }
    }
}