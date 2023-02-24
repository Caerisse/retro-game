package com.github.retro_game.retro_game.service.exception;

public class TargetIsAdminException extends ServiceException {
  public TargetIsAdminException() {
    super("Target is an admin");
  }
}
