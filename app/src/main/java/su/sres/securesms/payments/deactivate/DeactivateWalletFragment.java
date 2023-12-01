package su.sres.securesms.payments.deactivate;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.Navigation;

import su.sres.securesms.R;
import su.sres.securesms.payments.MoneyView;
import su.sres.securesms.util.SpanUtil;
import su.sres.securesms.util.views.LearnMoreTextView;

public class DeactivateWalletFragment extends Fragment {

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.deactivate_wallet_fragment, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    Toolbar           toolbar                   = view.findViewById(R.id.deactivate_wallet_fragment_toolbar);
    MoneyView         balance                   = view.findViewById(R.id.deactivate_wallet_fragment_balance);
    View              transferRemainingBalance  = view.findViewById(R.id.deactivate_wallet_fragment_transfer);
    View              deactivateWithoutTransfer = view.findViewById(R.id.deactivate_wallet_fragment_deactivate);
    LearnMoreTextView notice                    = view.findViewById(R.id.deactivate_wallet_fragment_notice);

    notice.setLearnMoreVisible(true);
    notice.setLink(getString(R.string.DeactivateWalletFragment__learn_more__we_recommend_transferring_your_funds));

    DeactivateWalletViewModel viewModel = ViewModelProviders.of(this).get(DeactivateWalletViewModel.class);

    viewModel.getBalance().observe(getViewLifecycleOwner(), balance::setMoney);
    viewModel.getDeactivationResults().observe(getViewLifecycleOwner(), r -> {
      if (r == DeactivateWalletViewModel.Result.SUCCESS) {
        Navigation.findNavController(requireView()).popBackStack();
      } else {
        Toast.makeText(requireContext(), R.string.DeactivateWalletFragment__error_deactivating_wallet, Toast.LENGTH_SHORT).show();
      }
    });

    transferRemainingBalance.setOnClickListener(v -> Navigation.findNavController(requireView()).navigate(R.id.action_deactivateWallet_to_paymentsTransfer));

    toolbar.setNavigationOnClickListener(v -> Navigation.findNavController(requireView()).popBackStack());

    //noinspection CodeBlock2Expr
    deactivateWithoutTransfer.setOnClickListener(v -> {
      new AlertDialog.Builder(requireContext())
                     .setTitle(R.string.DeactivateWalletFragment__deactivate_without_transferring_question)
                     .setMessage(R.string.DeactivateWalletFragment__your_balance_will_remain)
                     .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                     .setPositiveButton(SpanUtil.color(ContextCompat.getColor(requireContext(), R.color.signal_alert_primary),
                                                       getString(R.string.DeactivateWalletFragment__deactivate)),
                                        (dialog, which) -> {
                                          viewModel.deactivateWallet();
                                          dialog.dismiss();
                                        })
                     .show();
    });
  }
}
