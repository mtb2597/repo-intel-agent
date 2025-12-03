package com.csd.repointel.exception;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ModelAndView handleException(Exception ex, RedirectAttributes redirectAttributes) {
        // Optionally log the error
        // redirectAttributes.addFlashAttribute("error", ex.getMessage());
        return new ModelAndView("redirect:/scan");
    }
}
