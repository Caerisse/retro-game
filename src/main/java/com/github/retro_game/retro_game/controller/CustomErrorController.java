package com.github.retro_game.retro_game.controller;

import com.github.retro_game.retro_game.utils.Utils;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.mobile.device.Device;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;

import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;

@Controller
public class CustomErrorController implements ErrorController {

  public CustomErrorController() {

  }

//  @GetMapping("/error")
  @RequestMapping("/error")
  public String handleError(HttpServletRequest request, Device device, Model model) {
    Integer statusCode = (Integer) request.getAttribute("javax.servlet.error.status_code");
    Exception exception = (Exception) request.getAttribute("javax.servlet.error.exception");
    exception = (Exception) exception.getCause();
    model.addAttribute("status", statusCode);
    model.addAttribute("error", HttpStatus.valueOf(statusCode).getReasonPhrase());
    model.addAttribute("exception", exception.getClass().getName());
    model.addAttribute("message", exception.getMessage());
    model.addAttribute("timestamp", java.time.LocalDateTime.now().toString());
    model.addAttribute("trace", getStackTrace(exception));
    return Utils.getAppropriateView(device, "error");
  }
}
