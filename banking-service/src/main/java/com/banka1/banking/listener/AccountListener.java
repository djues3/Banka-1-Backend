package com.banka1.banking.listener;

import com.banka1.banking.dto.CreateAccountByEmployeeDTO;
import com.banka1.banking.services.AccountService;
import com.banka1.common.listener.MessageHelper;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccountListener {
    private final AccountService accountService;
    private final MessageHelper messageHelper;
    private final JmsTemplate jmsTemplate;

    @JmsListener(destination = "${destination.account}", concurrency = "5-10")
    public void onActivationMessage(Message message) throws JMSException {
        var dto = messageHelper.getMessage(message, CreateAccountByEmployeeDTO.class);
        try {
            if (dto != null)
                accountService.createAccount(dto.getCreateAccountDTO(), dto.getEmployeeId());
        } catch (Exception e) {
            log.error("AccountListener: ", e);
            jmsTemplate.convertAndSend(message.getJMSReplyTo(), messageHelper.createTextMessage(e.getMessage()));
            return;
        }
        jmsTemplate.convertAndSend(message.getJMSReplyTo(), messageHelper.createTextMessage(null));
    }
}
