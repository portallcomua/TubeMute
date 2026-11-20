<?php
/**
 * Plugin Name: TubeMute Access API
 * Description: Server-side registration, 7-day trials and temporary TubeMute activations.
 * Version: 1.0.0
 * Author: UA Server
 */

if (!defined('ABSPATH')) { exit; }

final class TubeMute_Access_API {
    const VERSION = '1.0.0';
    const USERS_URL = 'https://raw.githubusercontent.com/portallcomua/TubeMute/main/users.txt';
    const OPTION_DEVICES = 'tubemute_devices_v1';
    const OPTION_TEMP = 'tubemute_temp_activations_v1';

    public static function init() {
        add_action('rest_api_init', [__CLASS__, 'routes']);
        add_action('admin_menu', [__CLASS__, 'admin_menu']);
    }

    public static function routes() {
        register_rest_route('tubemute/v1', '/check', [
            'methods' => 'POST',
            'callback' => [__CLASS__, 'check'],
            'permission_callback' => '__return_true',
        ]);
        register_rest_route('tubemute/v1', '/activate', [
            'methods' => 'POST',
            'callback' => [__CLASS__, 'activate'],
            'permission_callback' => '__return_true',
        ]);
    }

    private static function input(WP_REST_Request $request) {
        $data = $request->get_json_params();
        if (!is_array($data)) { $data = []; }
        $email = isset($data['email']) ? strtolower(trim(sanitize_email($data['email']))) : '';
        $device = isset($data['device_id']) ? trim((string)$data['device_id']) : '';
        if (!is_email($email) || !preg_match('/@gmail\.com$/i', $email) || strlen($device) < 8 || strlen($device) > 200) {
            return new WP_Error('invalid_data', 'Invalid Gmail or device identifier.', ['status' => 400]);
        }
        return [$email, hash('sha256', $device)];
    }

    private static function users_list() {
        $cached = get_transient('tubemute_users_list_v1');
        if (is_string($cached)) { return $cached; }
        $response = wp_remote_get(self::USERS_URL, ['timeout' => 8, 'redirection' => 2]);
        if (is_wp_error($response) || wp_remote_retrieve_response_code($response) !== 200) {
            return new WP_Error('license_source_unavailable', 'License list is temporarily unavailable.', ['status' => 503]);
        }
        $body = wp_remote_retrieve_body($response);
        set_transient('tubemute_users_list_v1', $body, 60);
        return $body;
    }

    private static function permanent_license($email) {
        $list = self::users_list();
        if (is_wp_error($list)) { return $list; }
        foreach (preg_split('/\r\n|\r|\n/', $list) as $line) {
            $parts = array_map('trim', explode('|', $line));
            if (count($parts) < 2 || strtolower($parts[0]) !== $email) { continue; }
            $expiry = strtoupper($parts[1]);
            if ($expiry === 'LIFETIME') { return ['active' => true, 'until' => 'LIFETIME']; }
            $date = DateTime::createFromFormat('!Y-m-d', $expiry, wp_timezone());
            if ($date && $date->format('Y-m-d') === $expiry) {
                $date->modify('+1 day');
                if ($date > new DateTime('now', wp_timezone())) {
                    return ['active' => true, 'until' => $expiry];
                }
            }
        }
        return ['active' => false];
    }

    public static function check(WP_REST_Request $request) {
        $input = self::input($request);
        if (is_wp_error($input)) { return $input; }
        [$email, $device_hash] = $input;

        $license = self::permanent_license($email);
        if (is_wp_error($license)) { return $license; }
        if ($license['active']) {
            return rest_ensure_response(['status' => 'licensed', 'until' => $license['until']]);
        }

        $temporary = get_option(self::OPTION_TEMP, []);
        $temp_key = hash('sha256', $email . '|' . $device_hash);
        if (isset($temporary[$temp_key]) && !empty($temporary[$temp_key]['expires'])) {
            if ((int)$temporary[$temp_key]['expires'] > time()) {
                return rest_ensure_response([
                    'status' => 'temporary',
                    'until' => gmdate('Y-m-d', (int)$temporary[$temp_key]['expires']),
                ]);
            }
        }

        $devices = get_option(self::OPTION_DEVICES, []);
        if (!isset($devices[$device_hash])) {
            $now = time();
            $devices[$device_hash] = [
                'email' => $email,
                'first_seen' => $now,
                'trial_until' => $now + (7 * DAY_IN_SECONDS),
            ];
            update_option(self::OPTION_DEVICES, $devices, false);
        }

        $record = $devices[$device_hash];
        if (!hash_equals((string)$record['email'], $email)) {
            return rest_ensure_response(['status' => 'expired', 'reason' => 'device_email_mismatch']);
        }

        $remaining = (int)$record['trial_until'] - time();
        if ($remaining > 0) {
            return rest_ensure_response([
                'status' => 'trial',
                'days_left' => (int)ceil($remaining / DAY_IN_SECONDS),
                'trial_until' => gmdate('Y-m-d', (int)$record['trial_until']),
            ]);
        }

        return rest_ensure_response(['status' => 'expired']);
    }

    public static function activate(WP_REST_Request $request) {
        $input = self::input($request);
        if (is_wp_error($input)) { return $input; }
        [$email, $device_hash] = $input;

        $license = self::permanent_license($email);
        if (is_wp_error($license)) { return $license; }
        if ($license['active']) {
            return rest_ensure_response(['status' => 'licensed', 'until' => $license['until']]);
        }

        $devices = get_option(self::OPTION_DEVICES, []);
        if (!isset($devices[$device_hash]) || !hash_equals((string)$devices[$device_hash]['email'], $email)) {
            return new WP_Error('device_not_registered', 'This device has not been registered for this Gmail.', ['status' => 403]);
        }

        $temporary = get_option(self::OPTION_TEMP, []);
        $key = hash('sha256', $email . '|' . $device_hash);
        $expires = time() + (365 * DAY_IN_SECONDS);
        $temporary[$key] = [
            'email' => $email,
            'device_hash' => $device_hash,
            'created' => time(),
            'expires' => $expires,
        ];
        update_option(self::OPTION_TEMP, $temporary, false);

        return rest_ensure_response([
            'status' => 'temporary',
            'until' => gmdate('Y-m-d', $expires),
            'message' => 'Temporary access granted. Verify the payment manually.',
        ]);
    }

    public static function admin_menu() {
        add_menu_page(
            'TubeMute Access',
            'TubeMute Access',
            'manage_options',
            'tubemute-access',
            [__CLASS__, 'admin_page'],
            'dashicons-shield-alt'
        );
    }

    public static function admin_page() {
        if (!current_user_can('manage_options')) { return; }
        $temporary = get_option(self::OPTION_TEMP, []);
        if (isset($_POST['tubemute_revoke']) && check_admin_referer('tubemute_revoke_action')) {
            $key = sanitize_text_field(wp_unslash($_POST['tubemute_revoke']));
            if (isset($temporary[$key])) {
                unset($temporary[$key]);
                update_option(self::OPTION_TEMP, $temporary, false);
                echo '<div class="notice notice-success"><p>Temporary access revoked.</p></div>';
            }
        }
        $temporary = get_option(self::OPTION_TEMP, []);
        echo '<div class="wrap"><h1>TubeMute temporary activations</h1>';
        echo '<p>When a user presses Pay, access is granted for one year. Check the incoming payment and revoke access here if it was not paid.</p>';
        echo '<table class="widefat striped"><thead><tr><th>Gmail</th><th>Activated</th><th>Expires</th><th>Action</th></tr></thead><tbody>';
        if (!$temporary) {
            echo '<tr><td colspan="4">No temporary activations.</td></tr>';
        } else {
            foreach ($temporary as $key => $item) {
                echo '<tr><td>' . esc_html($item['email']) . '</td><td>' . esc_html(wp_date('Y-m-d H:i', (int)$item['created'])) . '</td><td>' . esc_html(wp_date('Y-m-d', (int)$item['expires'])) . '</td><td><form method="post">';
                wp_nonce_field('tubemute_revoke_action');
                echo '<input type="hidden" name="tubemute_revoke" value="' . esc_attr($key) . '">';
                submit_button('Revoke access', 'delete', 'submit', false);
                echo '</form></td></tr>';
            }
        }
        echo '</tbody></table></div>';
    }
}
TubeMute_Access_API::init();
