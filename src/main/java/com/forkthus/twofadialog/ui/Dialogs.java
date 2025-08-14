package com.forkthus.twofadialog.ui;

import com.forkthus.twofadialog.config.ConfigManager;
import io.papermc.paper.dialog.*;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.util.List;

public final class Dialogs {
    public static final Key ACTION_QR_GIVE   = Key.key("twf:qr/give");
    public static final Key ACTION_QR_EXIT   = Key.key("twf:qr/exit");
    public static final Key ACTION_ASK_DONE  = Key.key("twf:ask/finished");
    public static final Key ACTION_ASK_NOTY  = Key.key("twf:ask/notyet");
    public static final Key ACTION_LOGIN_SUB = Key.key("twf:login/submit");
    public static final Key ACTION_LOGIN_LEAVE = Key.key("twf:login/leave");

    public static Dialog scanPrompt(ConfigManager config, int timeLeft) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(config.getScanPromptTitle()))
                        .canCloseWithEscape(false)
                        .body(List.of(DialogBody.plainMessage(Component.text(config.getScanPromptBody(timeLeft)))))
                        .build())
                .type(DialogType.multiAction(
                        List.of(
                                ActionButton.create(
                                        Component.text(config.getScanPromptButtonText()),
                                        Component.text(config.getScanPromptButtonDesc()),
                                        160,
                                        DialogAction.customClick(ACTION_QR_GIVE, null)
                                ),
                                ActionButton.create(
                                        Component.text(config.getScanPromptExitButton()),
                                        Component.text(config.getScanPromptExitDesc()),
                                        160,
                                        DialogAction.customClick(ACTION_QR_EXIT, null)
                                )
                        ),
                        null, // No exit action since we handle exits through buttons
                        1 // 1 column to arrange buttons vertically
                ))
        );
    }

    public static Dialog askFinished(ConfigManager config, int timeLeft) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(config.getAskFinishedTitle()))
                        .body(List.of(DialogBody.plainMessage(Component.text(config.getAskFinishedBody(timeLeft)))))
                        .build())
                .type(DialogType.multiAction(
                        List.of(
                                ActionButton.create(
                                        Component.text(config.getAskFinishedYesButton()), 
                                        Component.text(config.getAskFinishedYesDesc()), 
                                        120,
                                        DialogAction.customClick(ACTION_ASK_DONE, null)
                                ),
                                ActionButton.create(
                                        Component.text(config.getAskFinishedNoButton()), 
                                        Component.text(config.getAskFinishedNoDesc()), 
                                        120,
                                        DialogAction.customClick(ACTION_ASK_NOTY, null)
                                )
                        ),
                        null, // No exit action
                        1 // 1 column to arrange buttons vertically
                ))
        );
    }

    public static Dialog login(ConfigManager config, int timeLeft, String errorOrNull) {
        return Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(config.getLoginTitle()))
                        .body(errorOrNull == null ? 
                            List.of(DialogBody.plainMessage(Component.text(config.getLoginBody(timeLeft)))) : 
                            List.of(
                                DialogBody.plainMessage(Component.text(errorOrNull)),
                                DialogBody.plainMessage(Component.text(config.getLoginBody(timeLeft)))
                            ))
                        .inputs(List.of(
                                DialogInput.text("otp", Component.text(config.getLoginOtpLabel())).width(220).build(),
                                DialogInput.bool("rules", Component.text(config.getLoginRulesLabel())).build()
                        ))
                        .build())
                .type(DialogType.multiAction(
                        List.of(
                                ActionButton.create(
                                        Component.text(config.getLoginSubmitButton()),
                                        Component.text(config.getLoginSubmitDesc()),
                                        120,
                                        DialogAction.customClick(ACTION_LOGIN_SUB, null)
                                ),
                                ActionButton.create(
                                        Component.text(config.getLoginLeaveButton()),
                                        Component.text(config.getLoginLeaveDesc()),
                                        120,
                                        DialogAction.customClick(ACTION_LOGIN_LEAVE, null)
                                )
                        ),
                        null, // No exit action
                        1 // 1 column to arrange buttons vertically
                ))
        );
    }
}
