package com.banka1.notification;

import com.banka1.notification.service.FirebaseService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class Test implements CommandLineRunner {

    private final FirebaseService firebaseService;

    public Test(FirebaseService firebaseService) {
        this.firebaseService = firebaseService;
    }

    @Override
    public void run(String... args) {
        System.out.println("---------------------------");

//        firebaseService.broadcastNotification("Hello from Banka1", "asdasdsad", null);
//        firebaseService.sendNotification("Hello from Banka1", "asdasdsad", "cE7XtQeg3UIfaoaazt-6ZX:APA91bEYirqxWJwe6g5fUTD1Z_YoEITHOgh3HHqPiEEOJR8rJcVOrSKcG-NLupDm-afYcuIu67xN9yl_VymQUHTI-3CteStDiigRS2BSWcZKPG_v9d9xDFo", Map.of());
    }
}
