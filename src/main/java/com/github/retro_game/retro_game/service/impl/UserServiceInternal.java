package com.github.retro_game.retro_game.service.impl;

import com.github.retro_game.retro_game.service.UserService;

interface UserServiceInternal extends UserService {
  boolean checkCurrentUserPassword(String password);
}
