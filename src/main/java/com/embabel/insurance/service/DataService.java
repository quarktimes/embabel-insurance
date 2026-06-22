package com.embabel.insurance.service;

import com.embabel.insurance.entity.Customer;
import com.embabel.insurance.entity.Vehicle;
import com.embabel.insurance.repository.CustomerRepository;
import com.embabel.insurance.repository.VehicleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 数据查询服务，封装客户和车辆的数据访问操作。
 *
 * <p>提供按 userId、车牌号、车型等维度的查询，支持精确匹配和模糊匹配回退。
 */
@Service
public class DataService {

    private final CustomerRepository customerRepository;
    private final VehicleRepository vehicleRepository;

    public DataService(CustomerRepository customerRepository, VehicleRepository vehicleRepository) {
        this.customerRepository = customerRepository;
        this.vehicleRepository = vehicleRepository;
    }

    public Optional<Customer> getCustomerByUserId(String userId) {
        return customerRepository.findByUserId(userId);
    }

    public Optional<Vehicle> getVehicleByLicensePlate(String licensePlate) {
        return vehicleRepository.findByLicensePlate(licensePlate);
    }

    public Optional<Vehicle> getVehicleByCustomerAndModel(Long customerId, String model) {
        var vehicles = vehicleRepository.findByCustomerId(customerId);
        // 1. Exact match (case-insensitive)
        return vehicles.stream()
                .filter(v -> v.getModel().equalsIgnoreCase(model))
                .findFirst()
                // 2. Fallback: model name contains the search term or vice versa
                .or(() -> vehicles.stream()
                        .filter(v -> v.getModel().toLowerCase().contains(model.toLowerCase())
                                || model.toLowerCase().contains(v.getModel().toLowerCase()))
                        .findFirst());
    }

    /**
     * 返回客户名下所有匹配指定车型的车辆。
     * 与 {@link #getVehicleByCustomerAndModel} 不同，本方法返回完整列表而非 Optional，
     * 以便调用方检测多个车辆匹配的歧义情况。
     */
    public List<Vehicle> getVehiclesByCustomerAndModel(Long customerId, String model) {
        var vehicles = vehicleRepository.findByCustomerId(customerId);
        // 1. Exact match (case-insensitive)
        var exactMatches = vehicles.stream()
                .filter(v -> v.getModel().equalsIgnoreCase(model))
                .toList();
        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        // 2. Fallback: model name contains the search term or vice versa
        return vehicles.stream()
                .filter(v -> v.getModel().toLowerCase().contains(model.toLowerCase())
                        || model.toLowerCase().contains(v.getModel().toLowerCase()))
                .toList();
    }

    public List<Vehicle> getVehiclesByCustomerId(Long customerId) {
        return vehicleRepository.findByCustomerId(customerId);
    }

    public Customer saveCustomer(Customer customer) {
        return customerRepository.save(customer);
    }

    public Vehicle saveVehicle(Vehicle vehicle) {
        return vehicleRepository.save(vehicle);
    }
}