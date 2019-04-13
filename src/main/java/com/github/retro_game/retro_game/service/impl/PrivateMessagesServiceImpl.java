package com.github.retro_game.retro_game.service.impl;

import com.github.retro_game.retro_game.model.entity.PrivateMessage;
import com.github.retro_game.retro_game.model.entity.User;
import com.github.retro_game.retro_game.model.repository.PrivateMessageRepository;
import com.github.retro_game.retro_game.model.repository.UserRepository;
import com.github.retro_game.retro_game.security.CustomUser;
import com.github.retro_game.retro_game.service.PrivateMessageService;
import com.github.retro_game.retro_game.service.dto.PrivateMessageDto;
import com.github.retro_game.retro_game.service.dto.PrivateMessageKindDto;
import com.github.retro_game.retro_game.service.exception.PrivateMessageDoesNotExist;
import com.github.retro_game.retro_game.service.exception.UnauthorizedPrivateMessageAccessException;
import com.github.retro_game.retro_game.service.exception.UserDoesntExistException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
class PrivateMessagesServiceImpl implements PrivateMessageService {
  private static final Logger logger = LoggerFactory.getLogger(PrivateMessagesServiceImpl.class);
  private final PrivateMessageRepository privateMessageRepository;
  private final UserRepository userRepository;

  public PrivateMessagesServiceImpl(PrivateMessageRepository privateMessageRepository, UserRepository userRepository) {
    this.privateMessageRepository = privateMessageRepository;
    this.userRepository = userRepository;
  }

  @Override
  public void send(long bodyId, long recipientId, String message) {
    long userId = CustomUser.getCurrentUserId();

    // FIXME: Instead of checking we could just try to insert and catch the exception.
    if (!userRepository.existsById(recipientId)) {
      logger.warn("Sending private message failed, recipient does not exist: userId={} recipientId={}", userId,
          recipientId);
      throw new UserDoesntExistException();
    }

    logger.info("Sending private message: userId={} recipientId={}", userId, recipientId);
    PrivateMessage m = new PrivateMessage();
    m.setSenderId(userId);
    m.setRecipientId(recipientId);
    m.setDeletedBySender(false);
    m.setDeletedByRecipient(false);
    m.setAt(Date.from(Instant.now()));
    m.setMessage(message);
    privateMessageRepository.save(m);
  }

  @Override
  public List<PrivateMessageDto> getMessages(long bodyId, PrivateMessageKindDto kind, Long correspondentId,
                                             Pageable pageable) {
    long userId = CustomUser.getCurrentUserId();

    List<PrivateMessage> messages;
    if (kind == PrivateMessageKindDto.RECEIVED) {
      if (correspondentId == null) {
        messages = privateMessageRepository.getByRecipientIdAndDeletedByRecipientIsFalseOrderByAtDesc(userId, pageable);
      } else {
        messages = privateMessageRepository.getByRecipientIdAndSenderIdAndDeletedByRecipientIsFalseOrderByAtDesc(userId,
            correspondentId, pageable);
      }
    } else {
      assert kind == PrivateMessageKindDto.SENT;
      if (correspondentId == null) {
        messages = privateMessageRepository.getBySenderIdAndDeletedBySenderIsFalseOrderByAtDesc(userId, pageable);
      } else {
        messages = privateMessageRepository.getBySenderIdAndRecipientIdAndDeletedBySenderIsFalseOrderByAtDesc(userId,
            correspondentId, pageable);
      }
    }

    // Get all ids.
    Set<Long> ids = new HashSet<>();
    for (PrivateMessage m : messages) {
      Long senderId = m.getSenderId();
      if (senderId != null)
        ids.add(senderId);

      Long recipientId = m.getRecipientId();
      if (recipientId != null)
        ids.add(recipientId);
    }

    // Fetch names.
    // TODO: use cache
    Map<Long, String> names = userRepository.findByIdIn(ids).stream()
        .collect(Collectors.toMap(
            User::getId,
            User::getName,
            (a, b) -> {
              throw new IllegalStateException();
            }
        ));

    return messages.stream()
        .map(m -> {
          Long senderId = m.getSenderId();
          String senderName = senderId != null ? names.get(senderId) : null;

          Long recipientId = m.getRecipientId();
          String recipientName = recipientId != null ? names.get(recipientId) : null;

          return new PrivateMessageDto(m.getId(), m.getAt(), senderId, senderName, recipientId, recipientName,
              m.getMessage());
        }).collect(Collectors.toList());
  }

  @Override
  @Transactional
  public void delete(long bodyId, PrivateMessageKindDto kind, long messageId) {
    long userId = CustomUser.getCurrentUserId();
    String kindStr = kind == PrivateMessageKindDto.RECEIVED ? "received" : "sent";

    Optional<PrivateMessage> messageOptional = privateMessageRepository.findById(messageId);
    if (!messageOptional.isPresent()) {
      logger.warn("Deleting {} private message failed, message does not exist: userId={} messageId={}", kindStr, userId,
          messageId);
      throw new PrivateMessageDoesNotExist();
    }
    PrivateMessage message = messageOptional.get();

    if (kind == PrivateMessageKindDto.RECEIVED && message.getRecipientId() == userId) {
      message.setDeletedByRecipient(true);
    } else if (kind == PrivateMessageKindDto.SENT && message.getSenderId() == userId) {
      message.setDeletedBySender(true);
    } else {
      logger.warn("Deleting {} private message failed, unauthorized access: userId={} messageId={}", kindStr, userId,
          messageId);
      throw new UnauthorizedPrivateMessageAccessException();
    }

    // If the message is now deleted by both the recipient and sender, we can remove it from the database.
    if (message.isDeletedByRecipient() && message.isDeletedBySender()) {
      privateMessageRepository.delete(message);
    }

    logger.info("Deleting {} private message successful: userId={} messageId={}", kindStr, userId, messageId);
  }

  @Override
  @Transactional
  public void deleteAll(long bodyId, PrivateMessageKindDto kind) {
    long userId = CustomUser.getCurrentUserId();

    if (kind == PrivateMessageKindDto.RECEIVED) {
      // Delete received messages permanently which are marked as deleted by sender (both deleted by sender & recipient
      // would be true). Mark remaining messages as deleted (only one flag, deleted by recipient, will be true).
      privateMessageRepository.deleteByRecipientIdAndDeletedBySenderIsTrue(userId);
      privateMessageRepository.markAllAsDeletedByRecipient(userId);
    } else {
      assert kind == PrivateMessageKindDto.SENT;
      privateMessageRepository.deleteBySenderIdAndDeletedByRecipientIsTrue(userId);
      privateMessageRepository.markAllAsDeletedBySender(userId);
    }

    String kindStr = kind == PrivateMessageKindDto.RECEIVED ? "received" : "sent";
    logger.info("Deleting all {} private messages successful: userId={}", kindStr, userId);
  }
}
