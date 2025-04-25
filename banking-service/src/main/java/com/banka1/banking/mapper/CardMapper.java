package com.banka1.banking.mapper;

import com.banka1.banking.dto.AuthorizedPersonDTO;
import com.banka1.banking.dto.CreateCardDTO;
import com.banka1.banking.models.AuthorizedPerson;
import com.banka1.banking.models.Card;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;

@Component
public class CardMapper {
    private static final Random rand = new Random();

    public Card dtoToCard(CreateCardDTO dto) {
        Card card = new Card();

        card.setCardType(dto.getCardType());
        card.setCardBrand(dto.getCardBrand());
        card.setCreatedAt(Instant.now().getEpochSecond());

        Calendar fiveYearsFuture = Calendar.getInstance();
        fiveYearsFuture.setTime(Date.from(Instant.now()));
        fiveYearsFuture.add(Calendar.YEAR, 5);

        card.setExpirationDate(fiveYearsFuture.getTime().getTime() / 1000);
        card.setActive(true);
        card.setBlocked(false);
        card.setCardLimit(1000000.0);

        String cardNumber = switch (card.getCardBrand()) {
            case VISA -> "4" + generateRandomNumeric(15);
            case MASTERCARD -> "51" + generateRandomNumeric(14);
            case DINA_CARD -> "9891" + generateRandomNumeric(12);
            case AMERICAN_EXPRESS -> "34" + generateRandomNumeric(14);
        };

        card.setCardNumber(cardNumber);
        card.setCardCvv(generateRandomNumeric(3));

        return card;
    }

    public AuthorizedPerson dtoToAuthorizedPerson(AuthorizedPersonDTO dto) {
        AuthorizedPerson authorizedPerson = new AuthorizedPerson();

        authorizedPerson.setFirstName(dto.getFirstName());
        authorizedPerson.setLastName(dto.getLastName());
        authorizedPerson.setPhoneNumber(dto.getPhoneNumber());
        authorizedPerson.setBirthDate(dto.getBirthDate());

        return authorizedPerson;
    }

    private static String generateRandomNumeric(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(rand.nextInt(10));
        }
        return builder.toString();
    }
}
