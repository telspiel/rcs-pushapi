package com.vts.rcs.pushapi.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vts.rcs.domain.constants.Constants;
import com.vts.rcs.domain.platform.*;
import com.vts.rcs.domain.pushapi.dto.WebEngageMessageRequest;
import com.vts.rcs.domain.service.*;
import com.vts.rcs.pushapi.domain.PushResponse;
import com.vts.rcs.pushapi.kafka.MessageProducer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@CrossOrigin
public class WebEngageMessageController {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Value("${push.api.instance.id}")
    private String instanceId;

    @Value("${push.api.kafka.topic}")
    public String topicName;

    @Autowired
    private MessageProducer producer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MisCurrDateService misCurrDateService;

    @Autowired
    private UserService userService;

    @Autowired
    private BotService botService;

    @Autowired
    private TemplateService templateService;

    @Autowired
    private VtUserSummaryService vtUserSummaryService;

    @PostMapping(value = "/webengage/sendmessage", produces = "application/json")
    public String sendWebEngageMessage(@RequestBody WebEngageMessageRequest request,
                                       HttpServletResponse resp,
                                       HttpServletRequest req) throws JsonProcessingException {

        logger.info("Received request to /webengage/sendmessage");
        
        req.getHeaderNames().asIterator().forEachRemaining(
                name -> logger.info("Header: {} = {}", name, req.getHeader(name)));

        List<PushResponse> responseList = new ArrayList<>();
        PushResponse response = new PushResponse();
        
        String clientIP = Optional.ofNullable(req.getHeader("X-Forwarded-For"))
                .orElse(Optional.ofNullable(req.getHeader("X-Real-IP")).orElse(req.getRemoteAddr()));
        logger.info("Client IP: {}", clientIP);

        try {
            if (request.getRcsData() == null || request.getRcsData().getToNumber() == null ||
                    request.getRcsData().getTemplateData() == null ||
                    request.getRcsData().getTemplateData().getTemplateName() == null) {

                logger.warn("Missing required fields in WebEngage request");
                response.setCode("-100");
                response.setDesc("Missing required fields");
                responseList.add(response);
                return objectMapper.writeValueAsString(responseList);
            }

            String[] phoneNumbers = request.getRcsData().getToNumber().split(",");
            String templateName = request.getRcsData().getTemplateData().getTemplateName();
            Template template = templateService.getByTemplateName(templateName);

//            String messageId = Optional.ofNullable(request.getMetadata())
//                    .map(WebEngageMessageRequest.Metadata::getMessageId)
//                    .orElse(new ObjectId().toHexString());

            VtUser user = userService.getUserByNameFromDb(template.getUserName());
            if (user == null) {
                logger.warn("Invalid user : User not found in DB");
                response.setCode("-101");
                response.setDesc("Invalid username");
                responseList.add(response);
                return objectMapper.writeValueAsString(responseList);
            }

//            String token = req.getHeader("authorization");
//            logger.info("token {}", token);
//            if (token == null || !token.equals(user.getApiKey())) {
//                logger.warn("Unauthorized access attempt for user: {}", user.getUserName());
//                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
//                return objectMapper.writeValueAsString(
//                        Collections.singletonMap("error", "Unauthorized: Invalid API Key")
//                );
//            }

            for (String rawNumber : phoneNumbers) {
                String number = rawNumber.trim().replaceFirst("^\\+", "");

                try {
                    logger.info("Processing WebEngage RCS message for: {}", number);
                    String messageId = new ObjectId().toHexString();
                    String conversationId = MessageObject.generateConversationId(number, instanceId);
                    String currentDateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                    MessageObject messageObject = buildRcsMessageObject(request, user, messageId, conversationId);
                    if (messageObject == null) {
                        response.setCode("-108");
                        response.setDesc("Bot or template not found.");
                        responseList.add(response);
                        continue;
                    }

                    messageObject.setDestNumber(number);
                    MisCurrDate savedMisCurr = misCurrDateService.saveAllMessageObjectInMisCurrDate(messageObject, user, messageId, null, null);

                    if (savedMisCurr != null) {
                        messageObject.setMisCurrDateId(savedMisCurr.getId().toString());
                        VtUserSummaryReport savedSummary = vtUserSummaryService.saveUserSummaryInDb(savedMisCurr, user);
                        if (savedSummary != null) {
                            messageObject.setVtUserSummaryId(savedSummary.getId().toString());
                        }
                    }

                    String jsonMessage = objectMapper.writeValueAsString(messageObject);
                    producer.send(topicName, jsonMessage);

                    response.setMsgId(messageId);
                    response.setCode("6001");
                    response.setDesc("WebEngage RCS message queued successfully.");
                    response.setReceiveTime(currentDateTime);
                    responseList.add(response);

                } catch (Exception e) {
                    logger.error("Exception while processing number: {}", number, e);
                    response.setCode("-500");
                    response.setDesc("Unexpected error occurred: " + e.getMessage());
                    responseList.add(response);
                }
            }

        } catch (Exception e) {
            logger.error("Exception while processing WebEngage message", e);
            response.setCode("-500");
            response.setDesc("Unexpected error occurred: " + e.getMessage());
            responseList.add(response);
        }

        return objectMapper.writeValueAsString(responseList);
    }

    private MessageObject buildRcsMessageObject(WebEngageMessageRequest request, VtUser user,
                                                String messageId,
                                                String conversationId) throws JsonProcessingException {

        String templateName = request.getRcsData().getTemplateData().getTemplateName();
        VtBot bot = null;
        Template template = null;

        try {
            template = templateService.getByTemplateName(templateName);
            if (template != null) {
                bot = botService.getBotDataByBotName(template.getBotName());
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch bot/template for WebEngage request: {}", templateName);
        }

        if (bot == null || template == null) {
        	logger.warn("Failed to fetch bot from Template: {}", templateName);
            return null;
        }

        Map<String, String> customParams = new HashMap<>();
        customParams.put("message_id", messageId);
        customParams.put("conversation_id", conversationId);

        if (request.getRcsData().getTemplateData().getParameters() != null) {
            customParams.putAll(request.getRcsData().getTemplateData().getParameters());
        }

        if (request.getRcsData().getCustomData() != null) {
            customParams.putAll(request.getRcsData().getCustomData());
        }

        MessageObject message = new MessageObject();
        message.setTemplateCode(template.getName());
        message.setTemplateId(template.getId().toString());
        message.setCustomParams(objectMapper.writeValueAsString(customParams));
        message.setMessageId(messageId);
        message.setUniqueId(messageId);
        message.setRouteId(1);
        message.setSplitCount(1);
        message.setUsername(user.getUserName());
        message.setConversationId(conversationId);
        message.setReceiveTime(new Timestamp(System.currentTimeMillis()));
        message.setBotName(bot.getBotName());
        message.setBotId(bot.getOperatorBotId());
        message.setVtBotId(bot.getId().toString());
        message.setBotType(bot.getBotType());
        message.setBrandName(bot.getCreationData().getData().getBrandDetails().getBrandName());
        message.setCampaignId(new ObjectId().toHexString());
        message.setCampaignName("WebEngageApiCamp_" + new Timestamp(System.currentTimeMillis()));
        message.setCustRef(request.getMetadata().getMessageId());

        String type = template.getType();
        if ("text_message".equalsIgnoreCase(type)) {
            message.setMessageType(template.getTextMessageContent() != null &&
                    template.getTextMessageContent().length() >= 160
                    ? Constants.MESSAGE_TYPE_RICH_STANDALONE : Constants.MESSAGE_TYPE_BASIC);
        } else if ("rich_card".equalsIgnoreCase(type)) {
            message.setMessageType(Constants.MESSAGE_TYPE_RICH_STANDALONE);
        } else if ("carousel".equalsIgnoreCase(type)) {
            message.setMessageType(Constants.MESSAGE_TYPE_RICH_CAROUSEL);
        }

        return message;
    }
}
