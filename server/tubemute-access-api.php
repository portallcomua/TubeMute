<?php
/**
 * Plugin Name: TubeMute
 * Description: Central user, trial, device and PRO access management for TubeMute Android.
 * Version: 2.3.2
 * Author: UA Server
 */
if (!defined('ABSPATH')) { exit; }

final class TubeMute {
    const VERSION = '2.3.2';
    const DB_VERSION = '2';
    const TABLE = 'tubemute_users';
    const DEVICES = 'tubemute_devices';
    const OPT = 'tubemute_settings';
    const CRON = 'tubemute_daily_reminders';

    public static function init() {
        register_activation_hook(__FILE__, [__CLASS__, 'activate']);
        add_action('plugins_loaded', [__CLASS__, 'maybe_upgrade']);
        add_action('rest_api_init', [__CLASS__, 'routes']);
        add_action('admin_menu', [__CLASS__, 'admin_menu']);
        add_action(self::CRON, [__CLASS__, 'send_due_reminders']);
        add_action('init', [__CLASS__, 'schedule']);
    }

    public static function maybe_upgrade() {
        if (get_option('tubemute_db_version') !== self::DB_VERSION) { self::activate(); }
    }

    private static function table($name) { global $wpdb; return $wpdb->prefix . $name; }
    private static function defaults() {
        return [
            'trial_days' => 7,
            'reminder_days' => 3,
            'admin_email' => get_option('admin_email'),
            'payment_url' => '',
            'paybox_service_id' => 0,
            'product_name' => 'TubeMute PRO',
            'webhook_secret' => '',
            'freemius_secret' => '',
            'freemius_product_id' => '',
            'freemius_plan_id' => '',
            'currency_label' => 'PRO',
        ];
    }
    private static function settings() { return wp_parse_args(get_option(self::OPT, []), self::defaults()); }

    public static function activate() {
        global $wpdb;
        require_once ABSPATH . 'wp-admin/includes/upgrade.php';
        $charset = $wpdb->get_charset_collate();
        $users = self::table(self::TABLE);
        $devices = self::table(self::DEVICES);
        dbDelta("CREATE TABLE $users (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            email varchar(190) NOT NULL,
            created_at datetime NOT NULL,
            trial_until datetime NOT NULL,
            access_until datetime NULL,
            lifetime tinyint(1) NOT NULL DEFAULT 0,
            status varchar(20) NOT NULL DEFAULT 'active',
            reminder_sent tinyint(1) NOT NULL DEFAULT 0,
            expired_notice_sent tinyint(1) NOT NULL DEFAULT 0,
            source varchar(40) NOT NULL DEFAULT 'trial',
            updated_at datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY email (email),
            KEY status (status)
        ) $charset;");
        dbDelta("CREATE TABLE $devices (
            id bigint(20) unsigned NOT NULL AUTO_INCREMENT,
            user_id bigint(20) unsigned NOT NULL,
            device_hash char(64) NOT NULL,
            first_seen datetime NOT NULL,
            last_seen datetime NOT NULL,
            PRIMARY KEY  (id),
            UNIQUE KEY user_device (user_id,device_hash),
            KEY device_hash (device_hash)
        ) $charset;");
        if (!get_option(self::OPT)) { add_option(self::OPT, self::defaults(), '', false); }
        update_option('tubemute_db_version', self::DB_VERSION);
        if (!wp_next_scheduled(self::CRON)) { wp_schedule_event(time() + HOUR_IN_SECONDS, 'daily', self::CRON); }
    }

    public static function schedule() {
        if (!wp_next_scheduled(self::CRON)) { wp_schedule_event(time() + HOUR_IN_SECONDS, 'daily', self::CRON); }
    }

    public static function routes() {
        register_rest_route('tubemute/v1', '/check', [
            'methods' => 'POST', 'callback' => [__CLASS__, 'check'], 'permission_callback' => '__return_true'
        ]);
        register_rest_route('tubemute/v1', '/webhook', [
            'methods' => 'POST', 'callback' => [__CLASS__, 'webhook'], 'permission_callback' => '__return_true'
        ]);
        register_rest_route('tubemute/v1', '/freemius/webhook', [
            'methods' => 'POST', 'callback' => [__CLASS__, 'freemius_webhook'], 'permission_callback' => '__return_true'
        ]);
    }

    private static function json_input(WP_REST_Request $request) {
        $data = $request->get_json_params();
        return is_array($data) ? $data : [];
    }

    private static function clean_email($email) {
        $email = strtolower(trim(sanitize_email((string)$email)));
        return is_email($email) ? $email : '';
    }

    private static function now_mysql() { return current_time('mysql', true); }
    private static function date_mysql($timestamp) { return gmdate('Y-m-d H:i:s', (int)$timestamp); }
    private static function timestamp($mysql) { return $mysql ? strtotime($mysql . ' UTC') : 0; }

    private static function get_user($email) {
        global $wpdb;
        return $wpdb->get_row($wpdb->prepare('SELECT * FROM ' . self::table(self::TABLE) . ' WHERE email=%s', $email), ARRAY_A);
    }

    private static function create_user($email) {
        global $wpdb;
        $now = time();
        $settings = self::settings();
        $days = max(1, min(365, (int)$settings['trial_days']));
        $wpdb->insert(self::table(self::TABLE), [
            'email' => $email,
            'created_at' => self::date_mysql($now),
            'trial_until' => self::date_mysql($now + $days * DAY_IN_SECONDS),
            'status' => 'active',
            'source' => 'trial',
            'updated_at' => self::date_mysql($now),
        ]);
        if ($wpdb->insert_id) {
            self::mail_admin('Новий користувач TubeMute', "Зареєстровано нового користувача: $email\nТестовий період: $days днів.");
            return self::get_user($email);
        }
        return self::get_user($email);
    }

    private static function register_device($user_id, $device_id) {
        global $wpdb;
        $hash = hash('sha256', $device_id);
        $table = self::table(self::DEVICES);
        $found = $wpdb->get_var($wpdb->prepare("SELECT id FROM $table WHERE user_id=%d AND device_hash=%s", $user_id, $hash));
        $now = self::now_mysql();
        if ($found) {
            $wpdb->update($table, ['last_seen' => $now], ['id' => (int)$found]);
        } else {
            $wpdb->insert($table, ['user_id' => $user_id, 'device_hash' => $hash, 'first_seen' => $now, 'last_seen' => $now]);
        }
    }

    private static function sync_paybox_license($user) {
        global $wpdb;
        $settings = self::settings();
        $service_id = absint($settings['paybox_service_id']);
        if (!$service_id || !$user) { return $user; }
        $table = $wpdb->prefix . 'upb_licenses';
        $exists = $wpdb->get_var($wpdb->prepare('SHOW TABLES LIKE %s', $wpdb->esc_like($table)));
        if ($exists !== $table) { return $user; }
        $license = $wpdb->get_row($wpdb->prepare(
            "SELECT status, expires FROM $table WHERE service_id=%d AND email=%s ORDER BY updated_at DESC, id DESC LIMIT 1",
            $service_id, $user['email']
        ), ARRAY_A);
        if (!$license) { return $user; }
        $expires = (int)$license['expires'];
        if ($license['status'] === 'active' && (!$expires || $expires > time())) {
            $lifetime = !$expires;
            $until = $lifetime ? null : self::date_mysql($expires);
            if ((int)$user['lifetime'] !== ($lifetime ? 1 : 0) || $user['access_until'] !== $until || $user['source'] !== 'ua_pay_box') {
                $wpdb->update(self::table(self::TABLE), [
                    'lifetime' => $lifetime ? 1 : 0,
                    'access_until' => $until,
                    'status' => 'active',
                    'source' => 'ua_pay_box',
                    'updated_at' => self::now_mysql()
                ], ['id' => (int)$user['id']]);
                $user = self::get_user($user['email']);
            }
        } elseif ($user['source'] === 'ua_pay_box') {
            $wpdb->update(self::table(self::TABLE), [
                'lifetime' => 0,
                'access_until' => self::date_mysql(time() - 60),
                'source' => 'ua_pay_box_expired',
                'updated_at' => self::now_mysql()
            ], ['id' => (int)$user['id']]);
            $user = self::get_user($user['email']);
        }
        return $user;
    }

    public static function check(WP_REST_Request $request) {
        $data = self::json_input($request);
        $email = self::clean_email($data['email'] ?? '');
        $device = sanitize_text_field($data['device_id'] ?? '');
        if (!$email || strlen($device) < 8 || strlen($device) > 200) {
            return new WP_Error('invalid_data', 'Enter a valid email and device identifier.', ['status' => 400]);
        }
        $user = self::get_user($email);
        if (!$user) { $user = self::create_user($email); }
        if (!$user) { return new WP_Error('registration_failed', 'Could not register this account.', ['status' => 500]); }
        self::register_device((int)$user['id'], $device);
        $user = self::sync_paybox_license($user);
        $settings = self::settings();
        $payment_url = esc_url_raw($settings['payment_url']);
        $now = time();

        if ($user['status'] === 'blocked') {
            return rest_ensure_response(['status' => 'blocked', 'payment_url' => $payment_url]);
        }
        if ((int)$user['lifetime'] === 1) {
            return rest_ensure_response(['status' => 'licensed', 'until' => 'LIFETIME', 'payment_url' => $payment_url]);
        }
        if (!empty($user['access_until']) && self::timestamp($user['access_until']) > $now) {
            return rest_ensure_response(['status' => 'licensed', 'until' => gmdate('Y-m-d', self::timestamp($user['access_until'])), 'payment_url' => $payment_url]);
        }
        $trial_until = self::timestamp($user['trial_until']);
        $remaining = $trial_until - $now;
        if ($remaining > 0) {
            $days = (int)ceil($remaining / DAY_IN_SECONDS);
            $reminder_days = max(0, (int)$settings['reminder_days']);
            $notice = ($days <= $reminder_days);
            if ($notice && !(int)$user['reminder_sent']) {
                self::mail_user($email, 'TubeMute: тестовий період скоро завершиться',
                    "Вітаємо! Ваш тестовий період TubeMute завершиться " . wp_date('d.m.Y', $trial_until) .
                    ". Щоб продовжити користування, відкрийте посилання для придбання PRO: " . $payment_url);
                global $wpdb;
                $wpdb->update(self::table(self::TABLE), ['reminder_sent' => 1, 'updated_at' => self::now_mysql()], ['id' => (int)$user['id']]);
            }
            return rest_ensure_response([
                'status' => 'trial', 'days_left' => $days,
                'trial_until' => gmdate('Y-m-d', $trial_until),
                'show_reminder' => $notice,
                'payment_url' => $payment_url
            ]);
        }
        if (!(int)$user['expired_notice_sent']) {
            self::mail_user($email, 'TubeMute: тестовий період завершився',
                "Ваш тестовий період TubeMute завершився. Придбати PRO можна тут: " . $payment_url);
            global $wpdb;
            $wpdb->update(self::table(self::TABLE), ['expired_notice_sent' => 1, 'updated_at' => self::now_mysql()], ['id' => (int)$user['id']]);
        }
        return rest_ensure_response(['status' => 'expired', 'payment_url' => $payment_url]);
    }

    public static function webhook(WP_REST_Request $request) {
        $settings = self::settings();
        $secret = (string)$settings['webhook_secret'];
        $provided = (string)$request->get_header('x-tubemute-secret');
        if (!$secret || !$provided || !hash_equals($secret, $provided)) {
            return new WP_Error('forbidden', 'Invalid webhook authentication.', ['status' => 403]);
        }
        $data = self::json_input($request);
        $email = self::clean_email($data['email'] ?? ($data['user']['email'] ?? ($data['customer']['email'] ?? '')));
        if (!$email) { return new WP_Error('invalid_email', 'Webhook must include the customer email.', ['status' => 400]); }
        $event = strtolower(sanitize_key($data['event'] ?? ($data['type'] ?? ($data['status'] ?? ''))));
        $paid = !empty($data['paid']) || in_array($event, ['payment.completed','payment.succeeded','subscription.active','license.created','license.activated','purchase.completed','active','paid'], true);
        $cancelled = in_array($event, ['subscription.cancelled','subscription.canceled','license.revoked','license.expired','cancelled','canceled','revoked','expired'], true);
        if (!$paid && !$cancelled) {
            return rest_ensure_response(['received' => true, 'updated' => false, 'reason' => 'event_not_entitling_access']);
        }
        global $wpdb;
        $user = self::get_user($email);
        if (!$user) { $user = self::create_user($email); }
        if (!$user) { return new WP_Error('user_not_found', 'Could not create user.', ['status' => 500]); }
        if ($cancelled) {
            $wpdb->update(self::table(self::TABLE), ['access_until' => self::date_mysql(time() - 60), 'lifetime' => 0, 'source' => 'webhook_cancelled', 'updated_at' => self::now_mysql()], ['id' => (int)$user['id']]);
            return rest_ensure_response(['received' => true, 'updated' => true, 'status' => 'cancelled']);
        }
        $lifetime = !empty($data['lifetime']);
        $expires = $data['expires_at'] ?? ($data['license']['expires_at'] ?? '');
        $until = $lifetime ? null : ($expires ? gmdate('Y-m-d H:i:s', is_numeric($expires) ? (int)$expires : strtotime((string)$expires)) : self::date_mysql(time() + 365 * DAY_IN_SECONDS));
        $wpdb->update(self::table(self::TABLE), [
            'access_until' => $until,
            'lifetime' => $lifetime ? 1 : 0,
            'status' => 'active',
            'source' => sanitize_key($data['source'] ?? 'payment_webhook'),
            'updated_at' => self::now_mysql()
        ], ['id' => (int)$user['id']]);
        self::mail_user($email, 'TubeMute PRO активовано', 'Оплату підтверджено. Доступ TubeMute PRO активовано.');
        self::mail_admin('TubeMute: підтверджено оплату', "PRO активовано для $email.");
        return rest_ensure_response(['received' => true, 'updated' => true, 'status' => 'licensed', 'until' => $lifetime ? 'LIFETIME' : $until]);
    }

    public static function freemius_webhook(WP_REST_Request $request) {
        $settings = self::settings();
        $secret = (string)$settings['freemius_secret'];
        $signature = trim((string)$request->get_header('x-signature'));
        $signature = preg_replace('/^sha256=/i', '', $signature);
        $raw = $request->get_body();
        if (!$secret || !$signature || !hash_equals(hash_hmac('sha256', $raw, $secret), $signature)) {
            return new WP_Error('invalid_signature', 'Invalid Freemius webhook signature.', ['status' => 401]);
        }
        $data = json_decode($raw, true);
        if (!is_array($data)) { return new WP_Error('invalid_payload', 'Invalid Freemius JSON payload.', ['status' => 400]); }
        $event_data = isset($data['data']) && is_array($data['data']) ? $data['data'] : $data;
        $product_id = (string)($data['plugin_id'] ?? ($data['plugin']['id'] ?? ''));
        $plan_id = (string)($event_data['plan_id'] ?? ($event_data['plan']['id'] ?? ($event_data['license']['plan_id'] ?? ($data['plan_id'] ?? ''))));
        if (!empty($settings['freemius_product_id']) && $product_id !== (string)$settings['freemius_product_id']) {
            return new WP_Error('wrong_product', 'Freemius product ID does not match.', ['status' => 403]);
        }
        if (!empty($settings['freemius_plan_id']) && $plan_id !== (string)$settings['freemius_plan_id']) {
            return new WP_Error('wrong_plan', 'Freemius plan ID does not match.', ['status' => 403]);
        }
        $event = strtolower((string)($data['type'] ?? ($data['event']['type'] ?? ($data['action'] ?? $request->get_header('x-event')))));
        $license = isset($event_data['license']) && is_array($event_data['license']) ? $event_data['license'] : $event_data;
        $email = self::clean_email($data['user']['email'] ?? ($event_data['user']['email'] ?? ($event_data['email'] ?? ($license['email'] ?? ''))));
        if (!$email) { return new WP_Error('missing_email', 'Freemius event does not contain a customer email.', ['status' => 400]); }
        $expires_value = $license['expiration'] ?? ($license['expiration_date'] ?? ($license['expires_at'] ?? 0));
        $expires = is_numeric($expires_value) ? (int)$expires_value : (is_string($expires_value) ? strtotime($expires_value) : 0);
        $revoked = (strpos($event, 'cancel') !== false || strpos($event, 'delete') !== false ||
            strpos($event, 'expir') !== false || strpos($event, 'refund') !== false ||
            strpos($event, 'deactiv') !== false || strpos($event, 'dispute.lost') !== false ||
            !empty($license['is_cancelled']) || !empty($license['is_deleted']) ||
            ($expires && $expires <= time()));
        $paid_event = (strpos($event, 'license') !== false || strpos($event, 'payment') !== false ||
            strpos($event, 'subscription') !== false || strpos($event, 'purchase') !== false);
        if (!$revoked && !$paid_event) {
            return rest_ensure_response(['received' => true, 'updated' => false, 'reason' => 'event_not_related_to_entitlement']);
        }
        global $wpdb;
        $user = self::get_user($email);
        if (!$user) { $user = self::create_user($email); }
        if (!$user) { return new WP_Error('user_not_found', 'Could not create TubeMute user.', ['status' => 500]); }
        if ($revoked) {
            $wpdb->update(self::table(self::TABLE), [
                'access_until' => self::date_mysql(time() - 60), 'lifetime' => 0,
                'source' => 'freemius_revoked', 'updated_at' => self::now_mysql()
            ], ['id' => (int)$user['id']]);
            return rest_ensure_response(['received' => true, 'status' => 'revoked', 'email' => $email]);
        }
        $until = $expires > time() ? self::date_mysql($expires) : self::date_mysql(time() + 365 * DAY_IN_SECONDS);
        $wpdb->update(self::table(self::TABLE), [
            'access_until' => $until, 'lifetime' => 0, 'status' => 'active',
            'source' => 'freemius', 'updated_at' => self::now_mysql()
        ], ['id' => (int)$user['id']]);
        self::mail_user($email, 'TubeMute PRO активовано', 'Freemius підтвердив покупку. Доступ TubeMute PRO активовано.');
        self::mail_admin('TubeMute: Freemius підтвердив оплату', 'PRO активовано для ' . $email . '.');
        return rest_ensure_response(['received' => true, 'status' => 'licensed', 'email' => $email, 'until' => $until]);
    }

    public static function send_due_reminders() {
        global $wpdb;
        $settings = self::settings();
        $days = max(0, (int)$settings['reminder_days']);
        $limit = self::date_mysql(time() + $days * DAY_IN_SECONDS);
        $users = self::table(self::TABLE);
        $rows = $wpdb->get_results($wpdb->prepare(
            "SELECT * FROM $users WHERE status='active' AND lifetime=0 AND (access_until IS NULL OR access_until<=%s) AND trial_until>%s AND trial_until<=%s AND reminder_sent=0",
            self::date_mysql(time()), self::now_mysql(), $limit
        ), ARRAY_A);
        foreach ($rows as $user) {
            self::mail_user($user['email'], 'TubeMute: тестовий період скоро завершиться',
                'Ваш тестовий період TubeMute завершується ' . wp_date('d.m.Y', self::timestamp($user['trial_until'])) .
                '. Придбати PRO: ' . esc_url_raw($settings['payment_url']));
            $wpdb->update($users, ['reminder_sent' => 1, 'updated_at' => self::now_mysql()], ['id' => (int)$user['id']]);
        }
    }

    private static function mail_user($email, $subject, $message) {
        wp_mail($email, $subject, $message, ['Content-Type: text/plain; charset=UTF-8']);
    }
    private static function mail_admin($subject, $message) {
        $email = sanitize_email(self::settings()['admin_email']);
        if ($email) { wp_mail($email, $subject, $message, ['Content-Type: text/plain; charset=UTF-8']); }
    }

    public static function admin_menu() {
        add_menu_page('TubeMute', 'TubeMute', 'manage_options', 'tubemute', [__CLASS__, 'admin_page'], 'dashicons-video-alt3');
    }

    public static function admin_page() {
        if (!current_user_can('manage_options')) { return; }
        global $wpdb;
        $settings = self::settings();
        $users_table = self::table(self::TABLE);
        if (isset($_POST['tm_save_settings']) && check_admin_referer('tm_settings')) {
            $settings['trial_days'] = max(1, min(365, absint($_POST['trial_days'] ?? 7)));
            $settings['reminder_days'] = min(30, absint($_POST['reminder_days'] ?? 3));
            $settings['admin_email'] = sanitize_email(wp_unslash($_POST['admin_email'] ?? ''));
            $settings['payment_url'] = esc_url_raw(wp_unslash($_POST['payment_url'] ?? ''));
            $settings['paybox_service_id'] = absint($_POST['paybox_service_id'] ?? 0);
            $settings['product_name'] = sanitize_text_field(wp_unslash($_POST['product_name'] ?? 'TubeMute PRO'));
            $settings['webhook_secret'] = sanitize_text_field(wp_unslash($_POST['webhook_secret'] ?? ''));
            $settings['freemius_secret'] = sanitize_text_field(wp_unslash($_POST['freemius_secret'] ?? ''));
            $settings['freemius_product_id'] = sanitize_text_field(wp_unslash($_POST['freemius_product_id'] ?? ''));
            $settings['freemius_plan_id'] = sanitize_text_field(wp_unslash($_POST['freemius_plan_id'] ?? ''));
            update_option(self::OPT, $settings, false);
            echo '<div class="notice notice-success"><p>Налаштування збережено.</p></div>';
        }
        if (isset($_POST['tm_add_user']) && check_admin_referer('tm_add_user')) {
            $email = self::clean_email(wp_unslash($_POST['new_email'] ?? ''));
            if ($email) {
                $user = self::get_user($email);
                if (!$user) { $user = self::create_user($email); }
                if ($user) {
                    $lifetime = !empty($_POST['lifetime']);
                    $days = max(1, min(365, absint($_POST['custom_days'] ?? 7)));
                    $wpdb->update($users_table, [
                        'lifetime' => $lifetime ? 1 : 0,
                        'access_until' => null,
                        'trial_until' => $lifetime ? $user['trial_until'] : self::date_mysql(time() + $days * DAY_IN_SECONDS),
                        'reminder_sent' => 0,
                        'expired_notice_sent' => 0,
                        'status' => 'active', 'source' => 'admin', 'updated_at' => self::now_mysql()
                    ], ['id' => (int)$user['id']]);
                    echo '<div class="notice notice-success"><p>Користувача збережено: ' . esc_html($email) . '</p></div>';
                }
            } else { echo '<div class="notice notice-error"><p>Вкажіть правильний email.</p></div>'; }
        }
        if (isset($_POST['tm_action'], $_POST['user_id']) && check_admin_referer('tm_user_action')) {
            $id = absint($_POST['user_id']);
            $action = sanitize_key($_POST['tm_action']);
            if ($action === 'lifetime') {
                $wpdb->update($users_table, ['lifetime'=>1,'access_until'=>null,'status'=>'active','source'=>'admin','updated_at'=>self::now_mysql()], ['id'=>$id]);
            } elseif ($action === 'revoke') {
                $wpdb->update($users_table, ['lifetime'=>0,'access_until'=>self::date_mysql(time()-60),'status'=>'active','source'=>'admin_revoked','updated_at'=>self::now_mysql()], ['id'=>$id]);
            } elseif ($action === 'block' || $action === 'unblock') {
                $wpdb->update($users_table, ['status'=>$action==='block'?'blocked':'active','updated_at'=>self::now_mysql()], ['id'=>$id]);
            }
            echo '<div class="notice notice-success"><p>Зміни збережено.</p></div>';
        }
        $settings = self::settings();
        $rows = $wpdb->get_results("SELECT * FROM $users_table ORDER BY created_at DESC LIMIT 500", ARRAY_A);
        $webhook_url = rest_url('tubemute/v1/webhook');
        echo '<div class="wrap"><h1>TubeMute v' . esc_html(self::VERSION) . ' — керування користувачами</h1>';
        echo '<h2>Налаштування</h2><form method="post">';
        wp_nonce_field('tm_settings');
        echo '<table class="form-table"><tr><th>Тестовий період, днів</th><td><input type="number" min="1" max="365" name="trial_days" value="'.esc_attr($settings['trial_days']).'"></td></tr>';
        echo '<tr><th>Нагадувати за, днів</th><td><input type="number" min="0" max="30" name="reminder_days" value="'.esc_attr($settings['reminder_days']).'"></td></tr>';
        echo '<tr><th>Email адміністратора</th><td><input type="email" class="regular-text" name="admin_email" value="'.esc_attr($settings['admin_email']).'"></td></tr>';
        echo '<tr><th>Посилання на оплату PRO (UA Pay Box)</th><td><input type="url" class="large-text" name="payment_url" value="'.esc_attr($settings['payment_url']).'"></td></tr>';
        echo '<tr><th>ID проєкту UA Pay Box</th><td><input type="number" min="1" name="paybox_service_id" value="'.esc_attr($settings['paybox_service_id']).'"><p class="description">TubeMute автоматично перевіряє таблицю ліцензій UA Pay Box у цій самій базі WordPress. Код UA Pay Box змінювати не потрібно.</p></td></tr>';
        echo '<tr><th>Назва продукту</th><td><input type="text" class="regular-text" name="product_name" value="'.esc_attr($settings['product_name']).'"></td></tr>';
        echo '<tr><th>Секрет webhook</th><td><input type="password" class="regular-text" name="webhook_secret" value="'.esc_attr($settings['webhook_secret']).'" autocomplete="new-password"><p class="description">Для власного платіжного webhook із заголовком X-TubeMute-Secret.</p></td></tr>';
        echo '<tr><th>Freemius Secret Key</th><td><input type="password" class="regular-text" name="freemius_secret" value="'.esc_attr($settings['freemius_secret']).'" autocomplete="new-password"></td></tr>';
        echo '<tr><th>Freemius Product ID</th><td><input type="text" name="freemius_product_id" value="'.esc_attr($settings['freemius_product_id']).'"></td></tr>';
        echo '<tr><th>Freemius Plan ID (необов’язково)</th><td><input type="text" name="freemius_plan_id" value="'.esc_attr($settings['freemius_plan_id']).'"></td></tr></table>';
        submit_button('Зберегти налаштування', 'primary', 'tm_save_settings');
        echo '</form><p><strong>Webhook URL:</strong> <code>'.esc_html($webhook_url).'</code></p>';
        echo '<hr><h2>Додати або змінити користувача</h2><form method="post">';
        wp_nonce_field('tm_add_user');
        echo '<input type="email" name="new_email" required placeholder="user@gmail.com" class="regular-text"> ';
        echo '<label><input type="checkbox" name="lifetime" value="1"> Довічний доступ</label> ';
        echo '<label>Тестових днів: <input type="number" name="custom_days" value="7" min="1" max="365" style="width:90px"></label> ';
        submit_button('Зберегти користувача', 'secondary', 'tm_add_user', false);
        echo '</form><hr><h2>Користувачі (останні 500)</h2>';
        echo '<table class="widefat striped"><thead><tr><th>Email</th><th>Реєстрація</th><th>Тест до</th><th>PRO до</th><th>Статус</th><th>Дії</th></tr></thead><tbody>';
        if (!$rows) { echo '<tr><td colspan="6">Поки немає користувачів.</td></tr>'; }
        foreach ($rows as $u) {
            $status = $u['status']==='blocked' ? 'Заблоковано' : ((int)$u['lifetime'] ? 'Довічний PRO' : (!empty($u['access_until']) && self::timestamp($u['access_until'])>time() ? 'PRO' : (self::timestamp($u['trial_until'])>time() ? 'Тест' : 'Тест завершено')));
            echo '<tr><td>'.esc_html($u['email']).'</td><td>'.esc_html($u['created_at']).'</td><td>'.esc_html($u['trial_until']).'</td><td>'.esc_html((int)$u['lifetime']?'Довічно':($u['access_until']?:'—')).'</td><td>'.esc_html($status).'</td><td><form method="post" style="display:flex;gap:5px;flex-wrap:wrap">';
            wp_nonce_field('tm_user_action');
            echo '<input type="hidden" name="user_id" value="'.(int)$u['id'].'">';
            echo '<button class="button" name="tm_action" value="lifetime">Довічно</button><button class="button" name="tm_action" value="revoke">Відкликати PRO</button><button class="button" name="tm_action" value="'.($u['status']==='blocked'?'unblock':'block').'">'.($u['status']==='blocked'?'Розблокувати':'Блокувати').'</button></form></td></tr>';
        }
        echo '</tbody></table></div>';
    }
}
TubeMute::init();
