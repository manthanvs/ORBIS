package com.orbis.app.ui;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orbis.app.R;

/**
 * The explainer. A tab rather than something behind the header: it is only useful
 * to someone who has not worked the app out yet, and that is exactly the person
 * who will not go looking in an overflow menu for it.
 */
public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        String version = "";
        try {
            version =
                    requireContext()
                            .getPackageManager()
                            .getPackageInfo(requireContext().getPackageName(), 0)
                            .versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen for our own package, and a blank version is harmless.
        }
        ((TextView) view.findViewById(R.id.about_version))
                .setText(getString(R.string.about_version, version == null ? "" : version));
    }
}
