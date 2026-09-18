package com.sentinelpay.application.port.in;

import com.sentinelpay.application.dto.AuthTokens;
import com.sentinelpay.application.dto.LoginCommand;

public interface AuthenticateUserUseCase {
    AuthTokens authenticate(LoginCommand command);
}
