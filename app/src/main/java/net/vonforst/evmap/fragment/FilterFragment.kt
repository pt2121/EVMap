package net.vonforst.evmap.fragment

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import net.vonforst.evmap.MapsActivity
import net.vonforst.evmap.R
import net.vonforst.evmap.adapter.FiltersAdapter
import net.vonforst.evmap.databinding.FragmentFilterBinding
import net.vonforst.evmap.ui.showEditTextDialog
import net.vonforst.evmap.viewmodel.FilterViewModel


class FilterFragment : Fragment() {
    private lateinit var binding: FragmentFilterBinding
    private val vm: FilterViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_filter, container, false)
        binding.lifecycleOwner = this
        binding.vm = vm

        setHasOptionsMenu(true)

        vm.filterProfile.observe(viewLifecycleOwner) {}

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)

        binding.filtersList.apply {
            adapter = FiltersAdapter()
            layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            addItemDecoration(
                DividerItemDecoration(
                    context, LinearLayoutManager.VERTICAL
                )
            )
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.filter, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_apply -> {
                lifecycleScope.launch {
                    vm.saveFilterValues()
                    findNavController().popBackStack()
                }
                true
            }
            R.id.menu_save_profile -> {
                saveProfile()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveProfile(error: Boolean = false) {
        showEditTextDialog(requireContext()) { dialog, input ->
            vm.filterProfile.value?.let { profile ->
                input.setText(profile.name)
            }

            if (error) {
                input.error = getString(R.string.required)
            }

            dialog.setTitle(R.string.save_as_profile)
                .setMessage(R.string.save_profile_enter_name)
                .setPositiveButton(R.string.ok) { di, button ->
                    if (input.text.isBlank()) {
                        saveProfile(true)
                    } else {
                        lifecycleScope.launch {
                            vm.saveAsProfile(input.text.toString())
                            findNavController().popBackStack()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { di, button ->

                }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.toolbar.setupWithNavController(
            findNavController(),
            (requireActivity() as MapsActivity).appBarConfiguration
        )

        vm.filterProfile.observe(viewLifecycleOwner) {
            if (it != null) {
                binding.toolbar.title = getString(R.string.edit_filter_profile, it.name)
            }
        }
    }
}