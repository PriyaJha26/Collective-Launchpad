package com.collectivelaunchpad.admin;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;

import com.collectivelaunchpad.shop.OrderService;

/**
 * Looking up something that does not exist, or that belongs to another seller or customer,
 * shows "not found" rather than an error page (and never reveals whether it exists).
 */
@ControllerAdvice(basePackages = {"com.collectivelaunchpad.admin", "com.collectivelaunchpad.site",
        "com.collectivelaunchpad.shop", "com.collectivelaunchpad.account", "com.collectivelaunchpad.seller"})
public class NotFoundAdvice {

    @ExceptionHandler({NoSuchElementException.class, OrderService.OrderException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView notFound() {
        ModelAndView mv = new ModelAndView("error");
        mv.addObject("status", 404);
        return mv;
    }
}
