package su.sres.securesms.payments;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import su.sres.securesms.database.PaymentDatabase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.payments.reconciliation.LedgerReconcile;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.FeatureFlags;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DataExportUtil {

  private DataExportUtil() {}

  public static @NonNull String createTsv() {
    if (!FeatureFlags.internalUser()) {
      throw new AssertionError("This is intended for internal use only");
    }

    if (Build.VERSION.SDK_INT < 26) {
      throw new AssertionError();
    }

    List<PaymentDatabase.PaymentTransaction> paymentTransactions = ShadowDatabase.payments()
                                                                                 .getAll();
    MobileCoinLedgerWrapper ledger     = SignalStore.paymentsValues().liveMobileCoinLedger().getValue();
    List<Payment>           reconciled = LedgerReconcile.reconcile(paymentTransactions, Objects.requireNonNull(ledger));

    return createTsv(reconciled);
  }

  @RequiresApi(api = 26)
  private static @NonNull String createTsv(@NonNull List<Payment> payments) {
    Context       context = ApplicationDependencies.getApplication();
    StringBuilder sb      = new StringBuilder();

    sb.append(String.format(Locale.US, "%s\t%s\t%s\t%s\t%s%n", "Date Time", "From", "To", "Amount", "Fee"));

    for (Payment payment : payments) {
      if (payment.getState() != State.SUCCESSFUL) {
        continue;
      }

      String self       = Recipient.self().getDisplayName(context);
      String otherParty = describePayee(context, payment.getPayee());
      String from;
      String to;
      switch (payment.getDirection()) {
        case SENT:
          from = self;
          to = otherParty;
          break;
        case RECEIVED:
          from = otherParty;
          to = self;
          break;
        default:
          throw new AssertionError();
      }
      sb.append(String.format(Locale.US, "%s\t%s\t%s\t%s\t%s%n",
                              DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(payment.getDisplayTimestamp())),
                              from,
                              to,
                              payment.getAmountWithDirection().requireMobileCoin().toBigDecimal(),
                              payment.getFee().requireMobileCoin().toBigDecimal()));
    }
    return sb.toString();
  }

  private static String describePayee(Context context, Payee payee) {
    if (payee.hasRecipientId()) {
      return Recipient.resolved(payee.requireRecipientId()).getDisplayName(context);
    } else if (payee.hasPublicAddress()) {
      return payee.requirePublicAddress().getPaymentAddressBase58();
    } else {
      return "Unknown";
    }
  }
}
