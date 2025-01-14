package su.sres.securesms.payments.create;

import androidx.annotation.NonNull;

import su.sres.core.util.money.FiatMoney;
import su.sres.securesms.payments.currency.CurrencyExchange;

import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.payments.Money;

final class InputState {
  private final InputTarget                             inputTarget;
  private final String                                  moneyAmount;
  private final String                                  fiatAmount;
  private final Optional<FiatMoney>                     fiatMoney;
  private final Money                                   money;
  private final Optional<CurrencyExchange.ExchangeRate> exchangeRate;

  InputState() {
    this(InputTarget.MONEY, "0", "0", Money.MobileCoin.ZERO, Optional.absent(), Optional.absent());
  }

  private InputState(@NonNull InputTarget inputTarget,
                     @NonNull String moneyAmount,
                     @NonNull String fiatAmount,
                     @NonNull Money money,
                     @NonNull Optional<FiatMoney> fiatMoney,
                     @NonNull Optional<CurrencyExchange.ExchangeRate> exchangeRate)
  {
    this.inputTarget  = inputTarget;
    this.moneyAmount  = moneyAmount;
    this.fiatAmount   = fiatAmount;
    this.money        = money;
    this.fiatMoney    = fiatMoney;
    this.exchangeRate = exchangeRate;
  }

  @NonNull String getFiatAmount() {
    return fiatAmount;
  }

  @NonNull Optional<FiatMoney> getFiatMoney() {
    return fiatMoney;
  }

  @NonNull String getMoneyAmount() {
    return moneyAmount;
  }

  @NonNull Money getMoney() {
    return money;
  }

  @NonNull InputTarget getInputTarget() {
    return inputTarget;
  }

  @NonNull Optional<CurrencyExchange.ExchangeRate> getExchangeRate() {
    return exchangeRate;
  }

  @NonNull InputState updateInputTarget(@NonNull InputTarget inputTarget) {
    return new InputState(inputTarget, moneyAmount, fiatAmount, money, fiatMoney, exchangeRate);
  }

  @NonNull InputState updateAmount(@NonNull String moneyAmount, @NonNull String fiatAmount, @NonNull Money money, @NonNull Optional<FiatMoney> fiatMoney) {
    return new InputState(inputTarget, moneyAmount, fiatAmount, money, fiatMoney, exchangeRate);
  }

  @NonNull InputState updateExchangeRate(@NonNull Optional<CurrencyExchange.ExchangeRate> exchangeRate) {
    return new InputState(inputTarget, moneyAmount, fiatAmount, money, fiatMoney, exchangeRate);
  }
}
