# Telegram Channels Integration Guide

## Overview

HH Assistant now supports monitoring Telegram channels as an additional source of vacancies alongside HH.ru API. This allows you to:

- Add Telegram channels that post job vacancies
- Monitor these channels for new vacancies
- Parse and analyze vacancies from channel messages
- Send relevant vacancies to your Telegram chat after analysis

## Adding a Telegram Channel

### Prerequisites

1. **Bot must be admin in the channel**: The bot needs to be added as an administrator in the channel to read messages
2. **Public channels**: For public channels, the bot just needs to be added as admin
3. **Private channels**: For private channels, you need to invite the bot using an invite link

### Adding a Channel via Telegram Commands

1. Add the bot to your channel as administrator:
   - Go to your channel settings
   - Click "Administrators"
   - Click "Add Admin"
   - Search for your bot by username
   - Confirm with appropriate permissions

2. Use the `/add_channel` command:
   ```
   /add_channel @channel_name
   ```
   
   Example: `/add_channel @devjobs_ua`

3. Start monitoring the channel:
   ```
   /monitor_channel @channel_name
   ```
   
   Example: `/monitor_channel @devjobs_ua`

### Managing Channels

#### View All Channels
```
/channels
```
Shows all added channels with their monitoring status and last update time.

#### Start/Stop Monitoring
```
/monitor_channel @channel_name
/stop_monitoring @channel_name
```
Control whether the bot actively fetches vacancies from the channel.

#### Remove Channel
```
/remove_channel @channel_name
```
Completely remove the channel from the system. The bot will also leave the channel.

## Supported Message Formats

The parser can extract vacancies from various message formats:

### Ideal Format
```
🔥 [HOT] Senior Java Developer needed at fintech startup

🏢 Company: FinTech Solutions
💰 Salary: $5000-7000
📍 Location: Remote (EU timezone)
💼 Experience: 5+ years
🔗 Link: https://example.com/job/123

Looking for a Senior Java Developer with experience in fintech...
```

### Simple Text Format
```
Position: Middle Frontend Developer (React)
Company: TechCorp
Salary: from $2000
Location: Kyiv
Requirements: React 3+, TypeScript, REST API
Contact: hr@techcorp.com
```

### Mixed Format with Emojis
```
💼 Java Developer (Spring Boot)
📍 Москва, офис
💰 250000-300000 руб.
📝 3+ года опыта
⏱️ Полная занятость
```

## Configuration

### Application Settings

The following configuration options are available in `application.yml`:

```yaml
app:
  telegram-channels:
    enabled: true
    fetch-interval: 900  # Every 15 minutes (same as HH.ru)
    messages-per-fetch: 100  # Number of messages to fetch per request
    min-relevance-score: 0.7  # Minimum score for channel vacancies
```

### Channel-Level Settings

Each channel can have individual settings:
- `minRelevanceScore`: Override global minimum relevance score
- `isMonitored`: Enable/disable monitoring for this specific channel
- `notes`: Add notes about the channel for reference

## Troubleshooting

### Bot Can't Read Channel Messages
1. **Check bot permissions**: Make sure the bot is an administrator with "Read Messages" permission
2. **Privacy settings**: Ensure the channel allows bot administrators to read messages
3. **API rate limits**: Telegram has rate limits; wait if you get rate limit errors

### Poor Vacancy Detection
1. **Keywords not detected**: Check that the message contains job-related keywords
2. **Language support**: The parser supports English, Russian, and Ukrainian keywords
3. **Custom formats**: Consider editing message format to match supported patterns

### Duplicate Vacancies
1. **Message tracking**: Vacancies are tracked by `message_id` + `channel_username`
2. **Edited messages**: If a message is edited, it might be detected as a new vacancy
3. **Database constraint**: Unique constraint prevents exact duplicates

## Integration with HH.ru

Vacancies from Telegram channels are:
1. **Parsed**: Extracted into standardized vacancy format
2. **Analyzed**: Processed through the same LLM analysis as HH.ru vacancies
3. **Filtered**: Subject to the same exclusion rules and content validation
4. **Queued**: Added to the same processing queue as HH.ru vacancies
5. **Notified**: Sent via the same notification system

## Rate Limits and Best Practices

### API Rate Limits
- Telegram Bot API: 30 messages per second
- Consider adding delays if you monitor many channels
- Implement backoff strategy when rate limit is hit

### Best Practices
1. **Start with a few channels**: Add 2-3 popular channels first
2. **Monitor channel quality**: Focus on channels with high-quality, relevant vacancies
3. **Regular cleanup**: Periodically review and remove inactive or low-quality channels
4. **Respect privacy**: Only add channels that you have permission to monitor

## Security Considerations

1. **Channel permissions**: Only add channels where you have authorization to monitor
2. **Data privacy**: All extracted vacancies are processed through your private LLM instance
3. **Bot security**: Use a dedicated bot token with limited permissions
4. **Access control**: Consider which users can add/remove channels in your deployment

## Example Channels for Testing

Good channels to test with (check local regulations):

- Public job posting channels
- Tech-specific communities
- Developer job boards
- Industry-specific vacancy boards

## Monitoring and Analytics

The system tracks:
- Channel fetch success/failure rates
- Vacancy detection accuracy
- Processing performance
- Duplicate prevention effectiveness

Monitor these metrics through the `/stats` command and application logs.
