package com.github.retro_game.retro_game.cron;

import com.github.retro_game.retro_game.entity.User;
import com.github.retro_game.retro_game.entity.UserRole;
import com.github.retro_game.retro_game.repository.UserRepository;
import com.github.retro_game.retro_game.service.RecordsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class UpdateRecordsTask {
  private static final Logger logger = LoggerFactory.getLogger(UpdateRecordsTask.class);
  private final UserRepository userRepository;
  private final RecordsService recordsService;

  public UpdateRecordsTask(UserRepository userRepository, RecordsService recordsService) {
    this.userRepository = userRepository;
    this.recordsService = recordsService;
  }

  @Scheduled(cron = "0 5,35 * * * *")
  private void updateRecords() {
    logger.info("Updating records");
    List<User> users = userRepository.findAll();
    for (User user : users) {
      if (user.getRoles() == UserRole.ADMIN) {
        logger.info("Skipping admin user: " + user.getName());
        continue;
      }
      logger.info("Updating records for user: " + user.getName());
      recordsService.share(0, true, true, true, true, true, user.getId());
    }
    logger.info("Finished updating records");
  }
}
